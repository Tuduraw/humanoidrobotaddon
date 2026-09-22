package com.example.humanoidrobotaddon.item;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import com.example.tudursvehiclemod.item.VehicleConverterTarget;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

/** Opts the humanoid robot into the base mod's own tiered base item -> spawner item converter, and
 * into packing a placed robot back up into a spawner item.
 *
 * <p>Registering this is entirely optional - an addon wanting a costlier, independent way of
 * obtaining its vehicles simply never registers a target. */
/** One base-mod converter/spawner category per robot entity type (the base mod matches a vehicle
 * JSON's own entity_type against exactly one category). */
public class RobotConverterTarget implements VehicleConverterTarget {

	private final String category;
	private final Item[] spawners;

	public RobotConverterTarget(String category, Item[] spawners) {
		this.category = category;
		this.spawners = spawners;
	}

	@Override
	public Identifier entityTypeId() {
		return Identifier.of(HumanoidRobotAddon.MOD_ID, this.category);
	}

	@Override
	public String translationKey() {
		return "item.humanoidrobotaddon.category." + this.category;
	}

	@Override
	public Identifier id() {
		return Identifier.of(HumanoidRobotAddon.MOD_ID, this.category);
	}

	@Override
	public Item[] tieredSpawnerItems() {
		return this.spawners;
	}
}
