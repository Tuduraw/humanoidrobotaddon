package com.example.humanoidrobotaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A "tracking channel": drives a base-mod WeaponPart from something other than a seat occupant's
 * view. Keyed by seat_index because getWeaponAimYaw()/getWeaponAimPitch() - the two methods the
 * whole part pipeline (pivot, aim_range, turret_rotation_speed, parent/child, fire direction) asks -
 * only receive the seat index. A part with no channel is untouched. The part must set
 * pilot_fallback: true, or the base mod treats its (nonexistent) seat as unmanned and freezes it.
 * Aim mode and gait swing are independent and combine. */
public record TrackingChannel(
		/** The {@code seat_index} that {@code weapon_parts} entries use to select this channel. */
		int seatIndex,
		Mode mode,
		/** Whether this channel oscillates with the walk cycle when it is not aiming at anything. */
		boolean swing,
		/** SWING: peak swing angle in degrees, at full stride. Scaled down at lower speeds - see
		 * HumanoidRobotEntity's own gait factor. */
		float amplitude,
		/** SWING: phase offset in degrees - 180 puts a limb in anti-phase with its opposite. */
		float phase,
		/** SWING: multiplies the gait phase. 1.0 means one full swing cycle per full
		 * {@code wheel_rotation_speed} revolution. */
		float cycleScale,
		/** Held pose (degrees, vehicle-relative yaw/pitch like every other channel angle) while in
		 * GLIDE / FLIGHT and NOT currently aiming at anything - e.g. legs folding back for flight.
		 * Blended in by the mode transition, so the limb eases into it rather than snapping. Absent =
		 * no pose for that mode (the channel behaves exactly as before there). */
		java.util.Optional<Pose> glidePose,
		java.util.Optional<Pose> flightPose,
		/** Weapon-system link: when true, this channel only RAISES its weapon (aims at a target, or
		 * follows the pilot's view) while that weapon is actually in use - this channel's own weapon
		 * is the selected one, or this channel holds a lock. Otherwise (another weapon selected and no
		 * lock, or nobody piloting at all) it stays lowered: swing/mode pose if it has them, else its
		 * rest angle - never the base mod's own pilot-view fallback. False (default) = the channel
		 * aims whenever it has something to aim at, exactly as before. */
		boolean stowWhenInactive,
		/** Pose held while LOWERED (stow_when_inactive, and neither selected nor locked) - e.g. a
		 * backpack cannon folded upright, which swings out (at turret_rotation_speed) when selected.
		 * Added on top of swing / mode pose. Absent = rest angle 0. */
		java.util.Optional<Pose> stowPose,
		/** Multi-target lock for a weapon on this seat: the designation key ADDS the candidate to this
		 * weapon's own target list (up to max_targets; the oldest drops off when full), every target is
		 * marked, and the lock-mode key switches to single-target mode (list of one). One trigger pull
		 * fires salvo_size rounds (capped by the magazine), spread evenly across the target list, oldest
		 * first - see weapon.MultiMissileBehavior, this addon's own registration into the base mod's
		 * WeaponType.CUSTOM extension point. Requires the weapon's own Type to actually be
		 * humanoidrobotaddon:multi_missile (or another weapon registered the same way) - this field alone
		 * does nothing for an ordinary Type = Missile weapon. Absent = ordinary single lock. */
		java.util.Optional<MultiLock> multiLock
) {

	/** See multiLock. salvo_size (rounds per trigger pull) defaults to max_targets. Applied by
	 * weapon.MultiMissileBehavior, the base mod's own CustomWeaponBehavior for this weapon's type. */
	public record MultiLock(int maxTargets, int salvoSize) {
		public static final Codec<MultiLock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.optionalFieldOf("max_targets", 4).forGetter(MultiLock::maxTargets),
				Codec.INT.optionalFieldOf("salvo_size", -1).forGetter(MultiLock::salvoSize)
		).apply(instance, MultiLock::new));

		public int effectiveSalvoSize() {
			return this.salvoSize > 0 ? this.salvoSize : Math.max(1, this.maxTargets);
		}
	}

	/** A held yaw/pitch pose, in degrees. */
	public record Pose(float yaw, float pitch) {
		public static final Codec<Pose> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter(Pose::yaw),
				Codec.FLOAT.optionalFieldOf("pitch", 0f).forGetter(Pose::pitch)
		).apply(instance, Pose::new));
	}

	/** Whether this channel holds a pose in either non-walking mode at all. */
	public boolean hasModePose() {
		return this.glidePose.isPresent() || this.flightPose.isPresent();
	}

	public enum Mode {
		/** No aim behaviour of its own - the part follows whoever is aiming it, exactly as the base
		 * mod would drive it. Combined with {@code swing: true} this is what a plain limb wants. */
		NONE,
		/** HARD LOCK. Points at the CONFIRMED lock and nothing else, for as long as it is held -
		 * the pilot may look anywhere. Once locked, only an explicit release or the target's own
		 * disappearance ends it. */
		LOCK,
		/** Aims at the current candidate (the crosshair-cone search) when this weapon has no lock. */
		ASSIST;

		public static final Codec<Mode> CODEC = Codec.STRING.xmap(
				name -> Mode.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)),
				mode -> mode.name().toLowerCase(java.util.Locale.ROOT));
	}

	public static final Codec<TrackingChannel> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.INT.fieldOf("seat_index").forGetter(TrackingChannel::seatIndex),
			Mode.CODEC.optionalFieldOf("mode", Mode.NONE).forGetter(TrackingChannel::mode),
			Codec.BOOL.optionalFieldOf("swing", false).forGetter(TrackingChannel::swing),
			Codec.FLOAT.optionalFieldOf("amplitude", 30.0f).forGetter(TrackingChannel::amplitude),
			Codec.FLOAT.optionalFieldOf("phase", 0.0f).forGetter(TrackingChannel::phase),
			Codec.FLOAT.optionalFieldOf("cycle_scale", 1.0f).forGetter(TrackingChannel::cycleScale),
			Pose.CODEC.optionalFieldOf("glide_pose").forGetter(TrackingChannel::glidePose),
			Pose.CODEC.optionalFieldOf("flight_pose").forGetter(TrackingChannel::flightPose),
			Codec.BOOL.optionalFieldOf("stow_when_inactive", false).forGetter(TrackingChannel::stowWhenInactive),
			Pose.CODEC.optionalFieldOf("stow_pose").forGetter(TrackingChannel::stowPose),
			MultiLock.CODEC.optionalFieldOf("multi_lock").forGetter(TrackingChannel::multiLock)
	).apply(instance, TrackingChannel::new));

	/** True if this channel has any aim behaviour at all - and therefore anything for manual mode
	 * to switch off. */
	public boolean aims() {
		return this.mode != Mode.NONE;
	}
}
