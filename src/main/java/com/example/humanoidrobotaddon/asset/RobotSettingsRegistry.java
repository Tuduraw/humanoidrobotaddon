package com.example.humanoidrobotaddon.asset;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Per-vehicle RobotSettings, keyed like the base mod's VehicleRegistry: reads the same
 * data/<namespace>/vehicles/*.json files and keeps only the "humanoid_robot" object (the
 * motorcycle addon's pattern). Vehicles from the external tudursvehiclemod-addons/ folder aren't
 * seen here and get DEFAULT. */
public final class RobotSettingsRegistry implements SimpleSynchronousResourceReloadListener {

	private static final Logger LOGGER = LoggerFactory.getLogger("HumanoidRobotAddon/Settings");
	private static final String DIRECTORY = "vehicles";
	private static final String SUFFIX = ".json";
	/** The one key inside a vehicle JSON that belongs to this addon. */
	private static final String SETTINGS_KEY = "humanoid_robot";

	private static Map<Identifier, RobotSettings> loaded = Map.of();

	/** Settings for one vehicle, or DEFAULT if it declared none. Never null. */
	public static RobotSettings get(Identifier vehicleId) {
		if (vehicleId == null) {
			return RobotSettings.DEFAULT;
		}
		return loaded.getOrDefault(vehicleId, RobotSettings.DEFAULT);
	}

	@Override
	public Identifier getFabricId() {
		return Identifier.of("humanoidrobotaddon", "robot_settings");
	}

	@Override
	public void reload(ResourceManager manager) {
		Map<Identifier, RobotSettings> result = new HashMap<>();

		for (Map.Entry<Identifier, Resource> entry :
				manager.findResources(DIRECTORY, id -> id.getPath().endsWith(SUFFIX)).entrySet()) {

			Identifier fileId = entry.getKey();
			String path = fileId.getPath();
			// Same id derivation the base mod's own listener uses, so the keys line up exactly.
			Identifier vehicleId = Identifier.of(
					fileId.getNamespace(),
					path.substring(DIRECTORY.length() + 1, path.length() - SUFFIX.length())
			);

			try (Reader reader = entry.getValue().getReader()) {
				JsonElement json = JsonParser.parseReader(reader);
				if (!json.isJsonObject()) {
					continue;
				}
				JsonObject obj = json.getAsJsonObject();
				if (!obj.has(SETTINGS_KEY)) {
					continue;
				}
				RobotSettings.CODEC.parse(JsonOps.INSTANCE, obj.get(SETTINGS_KEY))
						.resultOrPartial(error ->
								LOGGER.error("Failed to parse humanoid_robot settings for '{}': {}", vehicleId, error))
						.ifPresent(settings -> result.put(vehicleId, settings));
			} catch (Exception e) {
				LOGGER.error("Failed to read humanoid_robot settings for {}", vehicleId, e);
			}
		}

		loaded = Map.copyOf(result);
		LOGGER.info("Loaded humanoid robot settings for {} vehicle(s)", loaded.size());
	}
}
