package com.example.humanoidrobotaddon.asset;

/** The locomotion mode. Its speed / acceleration / fuel / turn multipliers are applied by scaling
 * the vehicle definition (HumanoidRobotEntity.getDefinition()), so every base-mod consumer of those
 * numbers (HUD, fuel gauge, turn gates) follows automatically. */
public enum MovementMode {
	/** Ordinary bipedal walking. Legs animate, Space climbs (see HumanoidRobotEntity's own
	 * tudursvehiclemod$applyWalkModeClimb()), and every multiplier is 1.0 by definition - WALK is
	 * the baseline the other two are multiples OF. */
	WALK,
	/** Ground boost: the robot stops walking and skates, limbs held still. Fast and thirsty, but
	 * deliberately sluggish to speed up or slow down. */
	GLIDE,
	/** Free 3D flight, flown like an aircraft - pitch and yaw follow the pilot's own view and thrust
	 * runs along that heading. Fastest and thirstiest, and by far the least responsive. */
	FLIGHT;

	/** The mode the toggle key's own SHORT press moves to from here - WALK and GLIDE alternate, and
	 * FLIGHT drops to GLIDE (per the requested control scheme: leaving flight needs no hold, only
	 * entering it does). */
	public MovementMode nextOnShortPress() {
		return switch (this) {
			case WALK -> GLIDE;
			case GLIDE -> WALK;
			case FLIGHT -> GLIDE;
		};
	}

	/** Whether the legs should be walking in this mode. Only WALK animates a gait; the other two
	 * hold the limbs still, which the gait factor handles by easing to zero (see
	 * HumanoidRobotEntity's own tudursvehiclemod$updateGaitFactor()) rather than snapping, so the
	 * robot visibly settles out of its stride as it transitions. */
	public boolean walks() {
		return this == WALK;
	}

	/** Whether this mode moves in three dimensions under pilot view control, rather than being
	 * bound to the ground. */
	public boolean flies() {
		return this == FLIGHT;
	}
}
