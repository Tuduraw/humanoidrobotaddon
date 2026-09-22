package com.example.humanoidrobotaddon.client.mixin;

import com.example.humanoidrobotaddon.client.AircraftFlightInputState;
import com.example.humanoidrobotaddon.client.HeadLookOffsetState;
import com.example.humanoidrobotaddon.entity.HumanoidRobotEntity;
import com.example.tudursvehiclemod.client.VehicleModClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures raw mouse movement while a HumanoidRobotEntity flies. flight_style "robot": into the
 * head-look offset (HeadLookOffsetState) that RobotCameraMixin composes onto the body. "aircraft":
 * into the body's own yaw/pitch input (AircraftFlightInputState), unless free-look is on. Never
 * cancels the vanilla call: the pilot's own yaw/pitch keep updating for the compass, F3, etc. */
@Mixin(Entity.class)
public abstract class RobotHeadLookMixin {

	/** Cursor-delta-to-degrees factor, matching the base mod's own PlayerLookRateMixin. */
	private static final float DEGREES_PER_RAW_UNIT = 0.06f;

	@Inject(method = "changeLookDirection(DD)V", at = @At("HEAD"))
	private void tudursvehiclemod$captureHeadLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!(self instanceof PlayerEntity player)) {
			return;
		}
		if (!(VehicleModClient.tudursvehiclemod$getClientEffectiveVehicle(player) instanceof HumanoidRobotEntity robot)) {
			return;
		}
		if (!robot.tudursvehiclemod$getMovementMode().flies()) {
			return;
		}
		if (robot.tudursvehiclemod$usesAircraftFlightControls()) {
			// "aircraft": the mouse steers the body (the base mod's aircraft mapping) unless free-look
			// is on; there is no head-look offset in this scheme.
			HeadLookOffsetState.yaw = 0f;
			HeadLookOffsetState.pitch = 0f;
			if (!robot.tudursvehiclemod$isEffectiveFreeLook(player)) {
				AircraftFlightInputState.yawDelta += (float) (cursorDeltaX * DEGREES_PER_RAW_UNIT);
				AircraftFlightInputState.pitchDelta += (float) (cursorDeltaY * DEGREES_PER_RAW_UNIT);
			}
			return;
		}
		float yawDelta = (float) (cursorDeltaX * DEGREES_PER_RAW_UNIT);
		float pitchDelta = (float) (cursorDeltaY * DEGREES_PER_RAW_UNIT);
		// Independent scalars (a quaternion accumulated by local rotations drifts into roll). Pitch
		// is negated: this composes onto the camera-space `fresh`, whose pitch sign is the opposite of
		// the body's.
		HeadLookOffsetState.yaw = MathHelper.wrapDegrees(HeadLookOffsetState.yaw - yawDelta);
		HeadLookOffsetState.pitch = MathHelper.clamp(HeadLookOffsetState.pitch - pitchDelta, -90f, 90f);
	}
}
