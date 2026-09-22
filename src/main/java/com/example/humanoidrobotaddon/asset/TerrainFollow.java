package com.example.humanoidrobotaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The {@code "terrain_follow"} object: ground sampled at four points (front/back/sides) gives a
 * pitch and roll, clamped and eased, for joints with a terrain_axis. Disabled when both max
 * angles are 0. Level while flying or airborne. */
public record TerrainFollow(float frontZ, float backZ, float halfWidth, float maxPitch, float maxRoll, float response) {

	public static final TerrainFollow NONE = new TerrainFollow(0f, 0f, 0f, 0f, 0f, 0.3f);

	public static final Codec<TerrainFollow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("front_z", 0f).forGetter(TerrainFollow::frontZ),
			Codec.FLOAT.optionalFieldOf("back_z", 0f).forGetter(TerrainFollow::backZ),
			Codec.FLOAT.optionalFieldOf("half_width", 0f).forGetter(TerrainFollow::halfWidth),
			Codec.FLOAT.optionalFieldOf("max_pitch", 0f).forGetter(TerrainFollow::maxPitch),
			Codec.FLOAT.optionalFieldOf("max_roll", 0f).forGetter(TerrainFollow::maxRoll),
			Codec.FLOAT.optionalFieldOf("response", 0.3f).forGetter(TerrainFollow::response)
	).apply(instance, TerrainFollow::new));

	public boolean enabled() {
		return this.maxPitch > 0f || this.maxRoll > 0f;
	}
}
