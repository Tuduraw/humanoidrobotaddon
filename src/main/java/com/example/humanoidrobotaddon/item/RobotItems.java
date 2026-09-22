package com.example.humanoidrobotaddon.item;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import com.example.tudursvehiclemod.item.TieredVehicleSpawnerItem;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** This addon's own items - the base mod's own TieredVehicleSpawnerItem, not a bespoke one, so
 * right-clicking a spawner opens the base mod's own vehicle selection screen filtered to robots of
 * that tier or below, with no screen or networking code here. */
public class RobotItems {

	/** [tier - 1]. Handed to the base mod's converter through RobotConverterTarget. */
	public static final Item[] ROBOT_SPAWNERS = new Item[5];

	public static final RobotConverterTarget TARGET = new RobotConverterTarget("humanoid_robot", ROBOT_SPAWNERS);

	public static void register() {
		registerSpawners("humanoid_robot", TARGET, ROBOT_SPAWNERS);

		RegistryKey<ItemGroup> groupKey =
				RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(HumanoidRobotAddon.MOD_ID, "humanoid_robots"));
		Registry.register(Registries.ITEM_GROUP, groupKey, FabricItemGroup.builder()
				.icon(() -> new ItemStack(ROBOT_SPAWNERS[0]))
				.displayName(Text.translatable("itemGroup.humanoidrobotaddon.humanoid_robots"))
				.build());

		ItemGroupEvents.modifyEntriesEvent(groupKey).register(entries -> {
			for (Item item : ROBOT_SPAWNERS) {
				entries.add(item);
			}
		});
	}

	private static void registerSpawners(String category, RobotConverterTarget target, Item[] into) {
		for (int tier = 1; tier <= 5; tier++) {
			String path = category + "_spawner_t" + tier;
			RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(HumanoidRobotAddon.MOD_ID, path));
			Item item = new TieredVehicleSpawnerItem(
					new Item.Settings().registryKey(key).maxCount(1), target, tier);
			into[tier - 1] = Registry.register(Registries.ITEM, key, item);
		}
	}
}
