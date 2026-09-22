package com.example.humanoidrobotaddon.asset;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The {@code "movement"} object: the three locomotion modes' tuning, climb, flight rates and
 * control scheme. Nested because RecordCodecBuilder.group() takes at most 16 fields. See README.md
 * for the schema. */
public record MovementSettings(
		/** WALK/GLIDE (both ground modes): upward speed (blocks/tick) while the pilot holds the jump
		 * key. Applied as a sustained climb rate rather than an impulse, and NOT held when the key
		 * is released - see HumanoidRobotEntity's own tudursvehiclemod$applyGroundModeClimb() for
		 * why this is deliberately not a helicopter-style hover. */
		float climbSpeed,
		/** GLIDE mode's own speed/acceleration/fuel multiples of the walking baseline. */
		ModeTuning glide,
		/** FLIGHT mode's own, likewise. */
		ModeTuning flight,
		/** FLIGHT: maximum yaw turn rate (degrees/tick) mouse-look input is clamped to before
		 * smoothing - a performance limit, not a hard cap on total rotation (unlike the old
		 * fixed-pitch-limit design, yaw/pitch here can accumulate past 360 with no bound). */
		float flightYawRateDegreesPerTick,
		/** FLIGHT: maximum pitch rate (degrees/tick), analogous to yaw rate. Faster than yaw by
		 * default, matching a real aircraft's own tighter pitch authority. */
		float flightPitchRateDegreesPerTick,
		/** FLIGHT: degrees per tick the robot rolls while A/D is held. */
		float flightRollDegreesPerTick,
		/** FLIGHT: how quickly the ACTUALLY-APPLIED yaw/pitch/roll delta eases toward its own
		 * target each tick, rather than snapping straight to it. 1.0 would snap instantly. */
		float flightRotationSmoothing,
		/** FLIGHT: consecutive ticks with no rotation input at all before the level-assist (eases
		 * yaw-preserving pitch to 0 and rolls to the nearest 90-degree increment) engages. */
		int flightLevelAssistGraceTicks,
		/** FLIGHT: how strongly the level-assist pulls toward level once engaged, per tick. */
		float flightLevelAssistSmoothing,
		/** FLIGHT: vertical speed (blocks/tick) while Space (up) or Shift (down) is held - a plain
		 * world-vertical translation added on top of thrust, the way a submarine's own ascend/descend
		 * works, not a pitch input. Nothing held = no vertical input at all (FLIGHT has no gravity,
		 * so the robot simply holds altitude). */
		float flightVerticalSpeed,
		/** How long (ticks) the mode key must be held to count as a LONG press (engage FLIGHT)
		 * rather than a short one. */
		int modeHoldTicks,
		/** Which modes the mode key may switch into: 0 = none (WALK only), 1 = GLIDE only,
		 * 2 = GLIDE and FLIGHT (default). For limiting lower-tier robots. */
		int modeAvailability,
		/** Ground-mode (WALK/GLIDE) climb budget, in ticks of held Space per airborne stretch:
		 * -1 = unlimited (default), 0 = no climb at all, N = climb stops after N ticks of input and the
		 * robot falls regardless of input, until it lands again (landing refills it). FLIGHT's own
		 * Space/Shift lift is never limited by this. */
		int climbLimitTicks,
		/** seat_index values of weapons that can't fire while in FLIGHT (arm weapons stowed by a
		 * transformation, say). Still selectable; their channel stays lowered in flight. */
		java.util.List<Integer> flightDisabledSeats,
		/** seat_index values of weapons that can ONLY fire while in FLIGHT (a transformed fighter's
		 * fixed nose guns, say). */
		java.util.List<Integer> flightOnlySeats,
		/** How FLIGHT is flown: "robot" (default) - arrow-key attitude, an always-free camera,
		 * Space/Shift lift; "aircraft" - the base mod's aircraft scheme: the mouse steers yaw/pitch,
		 * the camera locks to the nose (free-look key to look around), no vertical lift. For robots
		 * that transform into a fighter. */
		String flightStyle
) {

	/** One mode's own trade: how much faster it goes, how much less responsive it is, and how much
	 * more it burns - each as a multiple of this vehicle's own ordinary walking value. */
	/** turn: multiplier on turn_speed in this mode (default 1.0) - with flight_style "aircraft",
	 * the flight model derives its yaw/pitch rates from turn_speed like an aircraft does (x3 / x9),
	 * so a robot's walking turn_speed usually needs scaling down for flight. */
	public record ModeTuning(float speed, float acceleration, float fuel, float turn) {
		public ModeTuning(float speed, float acceleration, float fuel) {
			this(speed, acceleration, fuel, 1.0f);
		}

		public static Codec<ModeTuning> codec(float defaultSpeed, float defaultAcceleration, float defaultFuel) {
			return RecordCodecBuilder.create(instance -> instance.group(
					Codec.FLOAT.optionalFieldOf("speed", defaultSpeed).forGetter(ModeTuning::speed),
					Codec.FLOAT.optionalFieldOf("acceleration", defaultAcceleration).forGetter(ModeTuning::acceleration),
					Codec.FLOAT.optionalFieldOf("fuel", defaultFuel).forGetter(ModeTuning::fuel),
					Codec.FLOAT.optionalFieldOf("turn", 1.0f).forGetter(ModeTuning::turn)
			).apply(instance, ModeTuning::new));
		}
	}

	/** The requested defaults: glide is 3x speed / one-third acceleration / 3x fuel, flight is
	 * 5x speed / one-tenth acceleration / 5x fuel. */
	public static final ModeTuning GLIDE_DEFAULT = new ModeTuning(3.0f, 1.0f / 3.0f, 3.0f);
	public static final ModeTuning FLIGHT_DEFAULT = new ModeTuning(5.0f, 0.1f, 5.0f);

	public static final MovementSettings DEFAULT =
			new MovementSettings(0.35f, GLIDE_DEFAULT, FLIGHT_DEFAULT, 6.0f, 12.0f, 4.0f, 0.2f, 100, 0.02f, 0.4f, 6, 2, -1, java.util.List.of(), java.util.List.of(), "robot");

	public static final Codec<MovementSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.FLOAT.optionalFieldOf("climb_speed", 0.35f).forGetter(MovementSettings::climbSpeed),
			ModeTuning.codec(GLIDE_DEFAULT.speed(), GLIDE_DEFAULT.acceleration(), GLIDE_DEFAULT.fuel())
					.optionalFieldOf("glide", GLIDE_DEFAULT).forGetter(MovementSettings::glide),
			ModeTuning.codec(FLIGHT_DEFAULT.speed(), FLIGHT_DEFAULT.acceleration(), FLIGHT_DEFAULT.fuel())
					.optionalFieldOf("flight", FLIGHT_DEFAULT).forGetter(MovementSettings::flight),
			Codec.FLOAT.optionalFieldOf("flight_yaw_rate_degrees_per_tick", 6.0f).forGetter(MovementSettings::flightYawRateDegreesPerTick),
			Codec.FLOAT.optionalFieldOf("flight_pitch_rate_degrees_per_tick", 12.0f).forGetter(MovementSettings::flightPitchRateDegreesPerTick),
			Codec.FLOAT.optionalFieldOf("flight_roll_degrees_per_tick", 4.0f).forGetter(MovementSettings::flightRollDegreesPerTick),
			Codec.FLOAT.optionalFieldOf("flight_rotation_smoothing", 0.2f).forGetter(MovementSettings::flightRotationSmoothing),
			Codec.INT.optionalFieldOf("flight_level_assist_grace_ticks", 100).forGetter(MovementSettings::flightLevelAssistGraceTicks),
			Codec.FLOAT.optionalFieldOf("flight_level_assist_smoothing", 0.02f).forGetter(MovementSettings::flightLevelAssistSmoothing),
			Codec.FLOAT.optionalFieldOf("flight_vertical_speed", 0.4f).forGetter(MovementSettings::flightVerticalSpeed),
			Codec.INT.optionalFieldOf("mode_hold_ticks", 6).forGetter(MovementSettings::modeHoldTicks),
			Codec.INT.optionalFieldOf("mode_availability", 2).forGetter(MovementSettings::modeAvailability),
			Codec.INT.optionalFieldOf("climb_limit_ticks", -1).forGetter(MovementSettings::climbLimitTicks),
			Codec.INT.listOf().optionalFieldOf("flight_disabled_seats", java.util.List.of()).forGetter(MovementSettings::flightDisabledSeats),
			Codec.INT.listOf().optionalFieldOf("flight_only_seats", java.util.List.of()).forGetter(MovementSettings::flightOnlySeats),
			Codec.STRING.optionalFieldOf("flight_style", "robot").forGetter(MovementSettings::flightStyle)
	).apply(instance, MovementSettings::new));

	/** WALK is the baseline every other mode is a multiple OF, so it has no tuning record of its
	 * own and always reports 1.0. */
	public ModeTuning tuningFor(MovementMode mode) {
		return switch (mode) {
			case WALK -> new ModeTuning(1.0f, 1.0f, 1.0f);
			case GLIDE -> this.glide;
			case FLIGHT -> this.flight;
		};
	}
}
