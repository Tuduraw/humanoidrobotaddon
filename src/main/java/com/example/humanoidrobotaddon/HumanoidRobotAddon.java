package com.example.humanoidrobotaddon;

import com.example.humanoidrobotaddon.asset.RobotSettingsRegistry;
import com.example.humanoidrobotaddon.entity.HumanoidRobotEntity;
import com.example.humanoidrobotaddon.item.RobotItems;
import com.example.humanoidrobotaddon.network.RobotHeadLookPayload;
import com.example.humanoidrobotaddon.network.RobotOrientationInputPayload;
import com.example.humanoidrobotaddon.network.SetMovementModePayload;
import com.example.humanoidrobotaddon.network.ToggleLockModePayload;
import com.example.humanoidrobotaddon.weapon.MultiMissileBehavior;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import com.example.tudursvehiclemod.item.VehicleConverterTargets;
import com.example.tudursvehiclemod.registry.ModEntityTypes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.entity.EntityType;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

/** Adds walking humanoid / multi-legged robots on top of Tudur's Vehicle Mod. No mixins into the
 * base mod; two client-side mixins into vanilla for the FLIGHT camera (RobotHeadLookMixin,
 * RobotCameraMixin). Registers the entity type, the spawner category, the custom weapon type
 * (humanoidrobotaddon:multi_missile) and this addon's own C2S payloads; targeting reuses the base
 * mod's Carrier designation packets. See DEVELOPMENT_NOTES.md. */
public class HumanoidRobotAddon implements ModInitializer {

	public static final String MOD_ID = "humanoidrobotaddon";

	public static EntityType<HumanoidRobotEntity> HUMANOID_ROBOT;

	@Override
	public void onInitialize() {
		com.example.tudursvehiclemod.asset.CustomWeaponTypes.register(
				net.minecraft.util.Identifier.of(MOD_ID, "multi_missile"), MultiMissileBehavior.INSTANCE);

		// One entity type serves every tier - a light scout walker and a heavy assault frame differ
		// only in their own JSON, not in code.
		HUMANOID_ROBOT = ModEntityTypes.registerAddonVehicleType(
				Identifier.of(MOD_ID, "humanoid_robot"),
				HumanoidRobotEntity::new,
				1.6f, 4.0f);

		RobotItems.register();

		// Optional opt-in to the base mod's own tiered base item -> spawner conversion.
		VehicleConverterTargets.register(RobotItems.TARGET);

		// Reads this addon's own "humanoid_robot" object out of the same vehicle JSONs the base mod
		// parses - see RobotSettingsRegistry.
		ResourceManagerHelper.get(ResourceType.SERVER_DATA)
				.registerReloadListener(new RobotSettingsRegistry());

		PayloadTypeRegistry.playC2S().register(SetMovementModePayload.ID, SetMovementModePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SetMovementModePayload.ID, (payload, context) ->
				context.server().execute(() -> {
					// The base mod's own helper: resolves the vehicle the player is actually
					// controlling, including when seated in a child/turret seat.
					AbstractVehicleEntity vehicle =
							AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(context.player());
					if (vehicle instanceof HumanoidRobotEntity robot
							&& robot.getControllingPassenger() == context.player()) {
						robot.tudursvehiclemod$applyModeKey(payload.longPress());
					}
				}));

		PayloadTypeRegistry.playC2S().register(RobotOrientationInputPayload.ID, RobotOrientationInputPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RobotOrientationInputPayload.ID, (payload, context) ->
				context.server().execute(() -> {
					AbstractVehicleEntity vehicle =
							AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(context.player());
					if (vehicle instanceof HumanoidRobotEntity robot
							&& robot.getControllingPassenger() == context.player()) {
						robot.pendingYawInput += payload.yawDelta();
						robot.pendingPitchInput += payload.pitchDelta();
					}
				}));

		PayloadTypeRegistry.playC2S().register(ToggleLockModePayload.ID, ToggleLockModePayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ToggleLockModePayload.ID, (payload, context) ->
				context.server().execute(() -> {
					AbstractVehicleEntity vehicle =
							AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(context.player());
					if (vehicle instanceof HumanoidRobotEntity robot
							&& robot.getControllingPassenger() == context.player()) {
						robot.tudursvehiclemod$toggleLockFocus(context.player());
					}
				}));

		PayloadTypeRegistry.playC2S().register(RobotHeadLookPayload.ID, RobotHeadLookPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RobotHeadLookPayload.ID, (payload, context) ->
				context.server().execute(() -> {
					AbstractVehicleEntity vehicle =
							AbstractVehicleEntity.tudursvehiclemod$getEffectiveVehicle(context.player());
					if (vehicle instanceof HumanoidRobotEntity robot
							&& robot.getControllingPassenger() == context.player()) {
						robot.headLookYaw = payload.yaw();
						robot.headLookPitch = payload.pitch();
					}
				}));
	}
}
