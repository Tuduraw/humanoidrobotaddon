package com.example.humanoidrobotaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/** The {@code "humanoid_robot"} object inside an ordinary vehicle JSON (the base mod's codec
 * ignores keys it doesn't know, so one file describes one vehicle for both). Every field is
 * optional; a vehicle without the object gets DEFAULT. See README.md for the full schema. */
public record RobotSettings(
		/** Maximum distance (blocks) a target can be a CANDIDATE from - governs both the continuous
		 * candidate preview (and therefore ASSIST) and what a confirmed LOCK can be taken from.
		 * Deliberately not a distance at which an already-held lock breaks - see
		 * HumanoidRobotEntity's own tick() for why the lock has no range limit once taken. */
		float lockRange,
		/** Half-angle of the candidate-search cone around the pilot's own view direction, in
		 * degrees. This is the ONLY cone in the system - it defines both "close enough to preview
		 * as a candidate" and, by construction, "close enough for an ASSIST channel to track it",
		 * since ASSIST simply follows whatever the candidate currently is. */
		float lockConeDegrees,
		/** Height (blocks, vehicle-local) of the point every aiming channel measures its angle
		 * FROM - roughly the robot's own shoulder/sensor height. Aiming from the entity origin (its
		 * feet) would tip the arms noticeably downward on a close target. */
		float lockOriginY,
		/** Whether the current candidate is outlined for the pilot, through the base mod's own
		 * highlight system. Locks get their own dedicated red marker regardless. */
		boolean highlightTarget,
		/** Ground speed (blocks/tick) at or below which the gait is treated as stopped: swing
		 * amplitude reaches zero and the robot stands upright. */
		float swingMinSpeed,
		/** Ground speed (blocks/tick) at or above which the gait is at full stride. Normally the
		 * vehicle's own {@code max_speed}. */
		float swingFullSpeed,
		/** How quickly the swing amplitude eases between standing and full stride, per tick. 1.0
		 * would snap instantly. */
		float swingResponse,
		/** Gait cycle speed, applied to the same accumulated ground distance as the base mod's
		 * {@code wheel_rotation_speed} but kept separate: legs want ~3, wheels ~30+. */
		float gaitCycleSpeed,
		/** The three locomotion modes and their own trade-offs - see MovementSettings. */
		MovementSettings movement,
		/** Purely visual reactions to the current mode (deploying parts, body lean) - see
		 * ModeVisualSettings. */
		ModeVisualSettings modeVisuals,
		/** Whether a LOCK/ASSIST channel tied to a weapon compensates its pitch for that weapon's
		 * own gravity and travel time, rather than pointing straight at the target - see
		 * HumanoidRobotEntity's own tudursvehiclemod$computeBallisticPitch() doc. */
		boolean ballisticCompensation,
		/** Cap (degrees) on how much extra elevation ballistic compensation may add above the
		 * straight-line pitch. */
		float maxBallisticCompensationDegrees,
		/** See TrackingChannel. Empty means this robot drives every part the ordinary way. */
		List<TrackingChannel> channels,
		/** Multi-segment limb joints - see Joint. Empty = none. */
		List<Joint> joints,
		/** Ground sampling for terrain_axis joints - see TerrainFollow. */
		TerrainFollow terrainFollow
) {

	public static final RobotSettings DEFAULT = new RobotSettings(
			96.0f, 30.0f, 2.2f, true, 0.01f, 0.4f, 0.12f, 3.0f,
			MovementSettings.DEFAULT, ModeVisualSettings.DEFAULT, true, 45.0f, List.of(), List.of(), TerrainFollow.NONE);

	public static final Codec<RobotSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("lock_range", 96.0f).forGetter(RobotSettings::lockRange),
			Codec.FLOAT.optionalFieldOf("lock_cone_degrees", 30.0f).forGetter(RobotSettings::lockConeDegrees),
			Codec.FLOAT.optionalFieldOf("lock_origin_y", 2.2f).forGetter(RobotSettings::lockOriginY),
			Codec.BOOL.optionalFieldOf("highlight_target", true).forGetter(RobotSettings::highlightTarget),
			Codec.FLOAT.optionalFieldOf("swing_min_speed", 0.01f).forGetter(RobotSettings::swingMinSpeed),
			Codec.FLOAT.optionalFieldOf("swing_full_speed", 0.4f).forGetter(RobotSettings::swingFullSpeed),
			Codec.FLOAT.optionalFieldOf("swing_response", 0.12f).forGetter(RobotSettings::swingResponse),
			Codec.FLOAT.optionalFieldOf("gait_cycle_speed", 3.0f).forGetter(RobotSettings::gaitCycleSpeed),
			MovementSettings.CODEC.optionalFieldOf("movement", MovementSettings.DEFAULT).forGetter(RobotSettings::movement),
			ModeVisualSettings.CODEC.optionalFieldOf("mode_visuals", ModeVisualSettings.DEFAULT).forGetter(RobotSettings::modeVisuals),
			Codec.BOOL.optionalFieldOf("ballistic_compensation", true).forGetter(RobotSettings::ballisticCompensation),
			Codec.FLOAT.optionalFieldOf("max_ballistic_compensation_degrees", 45.0f).forGetter(RobotSettings::maxBallisticCompensationDegrees),
			TrackingChannel.CODEC.listOf().optionalFieldOf("channels", List.of()).forGetter(RobotSettings::channels),
			Joint.CODEC.listOf().optionalFieldOf("joints", List.of()).forGetter(RobotSettings::joints),
			TerrainFollow.CODEC.optionalFieldOf("terrain_follow", TerrainFollow.NONE).forGetter(RobotSettings::terrainFollow)
	).apply(instance, RobotSettings::new));

	/** The channel a given tracking seat index selects, or null if that index isn't a channel at
	 * all (in which case the base mod's own occupant-view behaviour is left completely alone -
	 * which is exactly what the head part, on seat 0, relies on). */
	public TrackingChannel channelFor(int seatIndex) {
		for (TrackingChannel channel : this.channels) {
			if (channel.seatIndex() == seatIndex) {
				return channel;
			}
		}
		return null;
	}
}
