package com.example.humanoidrobotaddon.weapon;

import com.example.humanoidrobotaddon.asset.TrackingChannel;
import com.example.humanoidrobotaddon.entity.HumanoidRobotEntity;
import com.example.tudursvehiclemod.asset.CustomWeaponBehavior;
import com.example.tudursvehiclemod.asset.WeaponDefinition;
import com.example.tudursvehiclemod.entity.AbstractVehicleEntity;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/** {@code Type = humanoidrobotaddon:multi_missile}: a lock-on missile whose lock target, salvo size
 * and per-round guidance come from this addon's own per-weapon locks (a multi_lock channel) instead
 * of the base mod's crosshair search. The salvo is spread round-robin over the locked targets;
 * single-target (focus) mode leaves one target, so every round homes on it. Registered once in
 * HumanoidRobotAddon. */
public final class MultiMissileBehavior implements CustomWeaponBehavior {

	public static final MultiMissileBehavior INSTANCE = new MultiMissileBehavior();

	private MultiMissileBehavior() {
	}

	@Override
	public boolean usesLockOn() {
		return true;
	}

	@Override
	public Entity resolveLockTarget(AbstractVehicleEntity vehicle, ServerPlayerEntity shooter, WeaponDefinition weapon) {
		// The most recently designated target for this seat, exactly like an ordinary single-lock
		// weapon's own indicator - the multi-target spread only matters for GUIDANCE, at fire time
		// (see resolveGuidanceTarget() below); the on-screen lock-on gauge and highlight track one
		// representative target the same way a single lock always has.
		if (vehicle instanceof HumanoidRobotEntity robot) {
			Entity designated = robot.tudursvehiclemod$getLockTarget(weapon.seatIndex());
			if (designated != null) {
				return designated;
			}
		}
		return CustomWeaponBehavior.super.resolveLockTarget(vehicle, shooter, weapon);
	}

	@Override
	public int getSalvoSize(AbstractVehicleEntity vehicle, WeaponDefinition weapon, int weaponIndex) {
		if (vehicle instanceof HumanoidRobotEntity robot) {
			TrackingChannel channel = robot.tudursvehiclemod$settings().channelFor(weapon.seatIndex());
			if (channel != null && channel.multiLock().isPresent()) {
				return channel.multiLock().get().effectiveSalvoSize();
			}
		}
		return CustomWeaponBehavior.super.getSalvoSize(vehicle, weapon, weaponIndex);
	}

	@Override
	public Entity resolveGuidanceTarget(AbstractVehicleEntity vehicle, ServerPlayerEntity shooter,
			WeaponDefinition weapon, int weaponIndex, int salvoIndex) {
		// Spreads the salvo across every locked target, oldest first, wrapping round-robin - so
		// rounds divide as evenly as the salvo size allows (8 rounds over 3 targets: 3/3/2). Single-
		// target (focus) mode naturally falls out of this too: the list holds exactly one target, so
		// every round in the salvo homes on it regardless of salvoIndex.
		if (vehicle instanceof HumanoidRobotEntity robot) {
			List<Integer> ids = robot.tudursvehiclemod$getAllLockLists().getOrDefault(weapon.seatIndex(), List.of());
			List<Entity> targets = new ArrayList<>();
			for (Integer id : ids) {
				Entity entity = robot.getEntityWorld().getEntityById(id);
				if (entity != null && entity.isAlive()) {
					targets.add(entity);
				}
			}
			if (!targets.isEmpty()) {
				return targets.get(Math.floorMod(salvoIndex, targets.size()));
			}
		}
		return this.resolveLockTarget(vehicle, shooter, weapon);
	}
}
