package com.example.humanoidrobotaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Optional;

/** One joint of a multi-segment limb: an OBJ group rotating about a hinge, optionally parented to
 * another joint or a weapon part. Angle = rest + gait swing + mode pose, clamped to [min, max],
 * then terrain conformance and free spin. Visual only (a joint never aims). See README.md. */
public record Joint(
		String part,
		Optional<String> parent,
		float pivotX, float pivotY, float pivotZ,
		float axisX, float axisY, float axisZ,
		float restAngle,
		Optional<Gait> gait,
		float glidePose,
		float flightPose,
		float minAngle,
		float maxAngle,
		/** Weapon-system link: the seat_index of a stow_when_inactive channel. While that weapon is
		 * raised (see TrackingChannel's own stowWhenInactive doc) this joint eases to ready_angle -
		 * e.g. an elbow straightening so the rifle lines up with the aim - and back to its ordinary
		 * rest/gait/pose angle once lowered. Absent = this joint ignores the weapon system. */
		Optional<Integer> readySeat,
		float readyAngle,
		/** Continuous rotation with ground travel, degrees per block (sign follows the direction of
		 * travel) - road wheels, rollers, sprockets. Added after clamping and never clamped, so it
		 * spins freely. 0 = none. */
		float spinSpeed,
		/** "pitch" or "roll": adds the terrain angle sampled under the robot (RobotSettings'
		 * terrain_follow) - for a leg/track unit that conforms to the ground while the torso above it
		 * stays level. Absent = none. */
		Optional<String> terrainAxis
) {

	/** Distance-driven swing, same phase source and gait factor as channel swing. */
	public record Gait(float amplitude, float phase, float cycleScale) {
		public static final Codec<Gait> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("amplitude", 0f).forGetter(Gait::amplitude),
				Codec.FLOAT.optionalFieldOf("phase", 0f).forGetter(Gait::phase),
				Codec.FLOAT.optionalFieldOf("cycle_scale", 1f).forGetter(Gait::cycleScale)
		).apply(instance, Gait::new));
	}

	/** Grouped only so the outer codec stays within RecordCodecBuilder's 16-field limit - a
	 * MapCodec, so these six keys still sit FLAT in the joint's own JSON object, not nested. */
	private record Geometry(float pivotX, float pivotY, float pivotZ, float axisX, float axisY, float axisZ) {
		static final MapCodec<Geometry> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("pivot_x", 0f).forGetter(Geometry::pivotX),
				Codec.FLOAT.optionalFieldOf("pivot_y", 0f).forGetter(Geometry::pivotY),
				Codec.FLOAT.optionalFieldOf("pivot_z", 0f).forGetter(Geometry::pivotZ),
				Codec.FLOAT.optionalFieldOf("axis_x", 1f).forGetter(Geometry::axisX),
				Codec.FLOAT.optionalFieldOf("axis_y", 0f).forGetter(Geometry::axisY),
				Codec.FLOAT.optionalFieldOf("axis_z", 0f).forGetter(Geometry::axisZ)
		).apply(instance, Geometry::new));
	}

	public static final Codec<Joint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("part").forGetter(Joint::part),
			Codec.STRING.optionalFieldOf("parent").forGetter(Joint::parent),
			Geometry.MAP_CODEC.forGetter(j -> new Geometry(j.pivotX, j.pivotY, j.pivotZ, j.axisX, j.axisY, j.axisZ)),
			Codec.FLOAT.optionalFieldOf("rest_angle", 0f).forGetter(Joint::restAngle),
			Gait.CODEC.optionalFieldOf("gait").forGetter(Joint::gait),
			Codec.FLOAT.optionalFieldOf("glide_pose", 0f).forGetter(Joint::glidePose),
			Codec.FLOAT.optionalFieldOf("flight_pose", 0f).forGetter(Joint::flightPose),
			Codec.FLOAT.optionalFieldOf("min_angle", -180f).forGetter(Joint::minAngle),
			Codec.FLOAT.optionalFieldOf("max_angle", 180f).forGetter(Joint::maxAngle),
			Codec.INT.optionalFieldOf("ready_seat").forGetter(Joint::readySeat),
			Codec.FLOAT.optionalFieldOf("ready_angle", 0f).forGetter(Joint::readyAngle),
			Codec.FLOAT.optionalFieldOf("spin_speed", 0f).forGetter(Joint::spinSpeed),
			Codec.STRING.optionalFieldOf("terrain_axis").forGetter(Joint::terrainAxis)
	).apply(instance, (part, parent, g, rest, gait, glide, flight, min, max, readySeat, readyAngle, spin, terrain) ->
			new Joint(part, parent, g.pivotX, g.pivotY, g.pivotZ, g.axisX, g.axisY, g.axisZ,
					rest, gait, glide, flight, min, max, readySeat, readyAngle, spin, terrain)));
}
