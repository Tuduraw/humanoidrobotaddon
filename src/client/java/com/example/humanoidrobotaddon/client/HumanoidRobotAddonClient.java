package com.example.humanoidrobotaddon.client;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import com.example.humanoidrobotaddon.entity.HumanoidRobotEntity;
import com.example.humanoidrobotaddon.network.RobotHeadLookPayload;
import com.example.humanoidrobotaddon.network.RobotOrientationInputPayload;
import com.example.humanoidrobotaddon.network.SetMovementModePayload;
import com.example.humanoidrobotaddon.network.ToggleLockModePayload;
import com.example.tudursvehiclemod.client.render.VehicleEntityRenderer;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** Client setup: the base mod's own VehicleEntityRenderer (unmodified - lean, joints and pivot
 * correction reach it through the base mod's hooks), the lock marker renderer, and this addon's
 * own keys: movement mode (V), lock mode (N), FLIGHT yaw/pitch arrows. Targeting reuses the base
 * mod's Carrier designation key. */
public class HumanoidRobotAddonClient implements ClientModInitializer {

	private static KeyBinding movementModeKey;
	private static KeyBinding lockModeKey;
	private static KeyBinding flightYawLeftKey;
	private static KeyBinding flightYawRightKey;
	private static KeyBinding flightPitchUpKey;
	private static KeyBinding flightPitchDownKey;

	/** Ticks the mode key has been held down for, or -1 while it isn't held. Needed because the
	 * short/long distinction can only be made on RELEASE: a press that turns out to be a hold must
	 * not have already fired as a short press on the way there. */
	private static int modeKeyHeldTicks = -1;

	/** Set once a hold has already fired FLIGHT, so the rest of that same hold is ignored and the
	 * eventual release doesn't then fire a second, short-press transition on top of it. */
	private static boolean modeKeyLongPressFired;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(HumanoidRobotAddon.HUMANOID_ROBOT, VehicleEntityRenderer::new);

		// Defaults to V, matching the base mod's own VTOL mode toggle - the two can never apply to
		// the same vehicle (a VtolEntity is not a HumanoidRobotEntity), so sharing the default
		// keeps "V changes how this thing moves" consistent across vehicle types, and either can
		// still be rebound independently.
		KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(HumanoidRobotAddon.MOD_ID, "humanoid_robot"));
		movementModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.humanoidrobotaddon.movement_mode",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_V,
				category
		));

		// FLIGHT mode's own yaw/pitch input - dedicated key pairs rather than the mouse (see
		// network.RobotOrientationInputPayload's own doc for why): held, not pressed, exactly like
		// the base mod's own sideways-input roll this addon already reuses for flight roll.
		flightYawLeftKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.humanoidrobotaddon.flight_yaw_left",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_LEFT,
				category
		));
		flightYawRightKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.humanoidrobotaddon.flight_yaw_right",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT,
				category
		));
		flightPitchUpKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.humanoidrobotaddon.flight_pitch_up",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UP,
				category
		));
		flightPitchDownKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.humanoidrobotaddon.flight_pitch_down",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_DOWN,
				category
		));

		// Multi-target / single-target switch for the selected multi_lock missile weapon.
		lockModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.humanoidrobotaddon.lock_mode",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_N,
				category
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LockMarkerRenderer.tick(client);
			tudursvehiclemod$tickModeKey(client);
			tudursvehiclemod$tickFlightOrientationInput(client);
			tudursvehiclemod$tickHeadLookSync(client);
			while (lockModeKey.wasPressed()) {
				if (client.player != null
						&& AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(client.player) instanceof HumanoidRobotEntity robot
						&& robot.getControllingPassenger() == client.player) {
					ClientPlayNetworking.send(new ToggleLockModePayload());
				}
			}
		});
	}

	/** Resolves the mode key into a short or long press by hold duration: FLIGHT fires the moment the
		 * hold threshold is crossed; the short press fires on release if it never was. */
	private static void tudursvehiclemod$tickModeKey(net.minecraft.client.MinecraftClient client) {
		if (client.player == null) {
			modeKeyHeldTicks = -1;
			modeKeyLongPressFired = false;
			return;
		}

		AbstractVehicleEntity vehicle =
				AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(client.player);
		boolean pilotingRobot = vehicle instanceof HumanoidRobotEntity
				&& vehicle.getControllingPassenger() == client.player;

		// Drain any presses made while not piloting a robot, so they don't fire later.
		while (movementModeKey.wasPressed()) {
			// discard
		}
		if (!pilotingRobot) {
			modeKeyHeldTicks = -1;
			modeKeyLongPressFired = false;
			return;
		}

		// The threshold is the ROBOT's own configured mode_hold_ticks - read from the client's copy
		// of the entity, which has the same synced definition id the server resolves settings from.
		int holdTicks = Math.max(1, ((HumanoidRobotEntity) vehicle).tudursvehiclemod$settings().movement().modeHoldTicks());

		if (movementModeKey.isPressed()) {
			modeKeyHeldTicks = modeKeyHeldTicks < 0 ? 0 : modeKeyHeldTicks + 1;
			if (!modeKeyLongPressFired && modeKeyHeldTicks >= holdTicks) {
				modeKeyLongPressFired = true;
				ClientPlayNetworking.send(new SetMovementModePayload(true));
			}
			return;
		}

		if (modeKeyHeldTicks >= 0) {
			if (!modeKeyLongPressFired) {
				ClientPlayNetworking.send(new SetMovementModePayload(false));
			}
			modeKeyHeldTicks = -1;
			modeKeyLongPressFired = false;
		}
	}

	/** FLIGHT yaw/pitch input: arrow keys (and, for flight_style "aircraft", the accumulated mouse
		 * delta), applied to the local copy immediately and sent to the server. Keys send a fixed
		 * magnitude - the receiver clamps to the configured per-tick rate. */
	private static final float ROTATION_KEY_INPUT_MAGNITUDE = 1000f;

	private static void tudursvehiclemod$tickFlightOrientationInput(net.minecraft.client.MinecraftClient client) {
		if (client.player == null) {
			return;
		}
		if (!(AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(client.player) instanceof HumanoidRobotEntity robot)
				|| robot.getControllingPassenger() != client.player) {
			return;
		}
		// No free-look check here (an earlier revision had one) - see HumanoidRobotEntity's own
		// tudursvehiclemod$isEffectiveFreeLook() doc for why: rotation is entirely key-driven now,
		// so free-look (which only ever meant "the mouse is busy steering, not looking") no longer
		// describes anything that could conflict with it.
		if (!robot.tudursvehiclemod$getMovementMode().flies()) {
			AircraftFlightInputState.yawDelta = 0f;
			AircraftFlightInputState.pitchDelta = 0f;
			return;
		}

		float yawDelta = 0f;
		float pitchDelta = 0f;
		// Aircraft-style flight (movement.flight_style "aircraft"): this tick's accumulated mouse movement
		// steers the body too, through the same payload as the arrow keys (see
		// client.mixin.RobotHeadLookMixin for where it's captured).
		if (robot.tudursvehiclemod$usesAircraftFlightControls()) {
			yawDelta += AircraftFlightInputState.yawDelta;
			pitchDelta += AircraftFlightInputState.pitchDelta;
		}
		AircraftFlightInputState.yawDelta = 0f;
		AircraftFlightInputState.pitchDelta = 0f;
		if (flightYawLeftKey.isPressed()) {
			yawDelta -= ROTATION_KEY_INPUT_MAGNITUDE;
		}
		if (flightYawRightKey.isPressed()) {
			yawDelta += ROTATION_KEY_INPUT_MAGNITUDE;
		}

		// Negative pitch is "up" in this vehicle's own convention (matching Minecraft's own pitch
		// sign) - the UP arrow therefore contributes a negative delta.
		if (flightPitchUpKey.isPressed()) {
			pitchDelta -= ROTATION_KEY_INPUT_MAGNITUDE;
		}
		if (flightPitchDownKey.isPressed()) {
			pitchDelta += ROTATION_KEY_INPUT_MAGNITUDE;
		}

		if (yawDelta == 0f && pitchDelta == 0f) {
			return;
		}

		robot.pendingYawInput += yawDelta;
		robot.pendingPitchInput += pitchDelta;
		ClientPlayNetworking.send(new RobotOrientationInputPayload(yawDelta, pitchDelta));
	}

	/** Mirrors the current head-look offset to the server every FLIGHT tick (state, not input - sent
		 * whether or not it changed). */
	private static void tudursvehiclemod$tickHeadLookSync(net.minecraft.client.MinecraftClient client) {
		if (client.player == null) {
			return;
		}
		if (!(AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(client.player) instanceof HumanoidRobotEntity robot)
				|| robot.getControllingPassenger() != client.player) {
			return;
		}
		if (!robot.tudursvehiclemod$getMovementMode().flies()) {
			return;
		}
		robot.headLookYaw = HeadLookOffsetState.yaw;
		robot.headLookPitch = HeadLookOffsetState.pitch;
		ClientPlayNetworking.send(new RobotHeadLookPayload(HeadLookOffsetState.yaw, HeadLookOffsetState.pitch));
	}
}
