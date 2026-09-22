package com.example.humanoidrobotaddon.client;

/** Raw mouse yaw-look delta accumulated between client ticks, in degrees, while the local player is
 * piloting a HumanoidRobotEntity in FLIGHT mode - written by client.mixin.RobotLookRateMixin
 * (possibly several times per tick, since raw mouse events can arrive faster than the tick rate),
 * drained once per tick by HumanoidRobotAddonClient's own END_CLIENT_TICK hook.
 *
 * <p>Yaw only - see RobotLookRateMixin's own doc for why pitch input moved to a dedicated key pair
 * instead of the pilot's own mouse Y.
 *
 * <p>A plain static holder rather than a field on the entity itself: the mixin runs inside vanilla
 * Entity code, with no clean route to "the HumanoidRobotEntity the local player currently happens to
 * be piloting" from there - the client tick handler already has that lookup, and reads this instead.
 * Mirrors the base mod's own client.AircraftOrientationInputState in shape. */
public final class RobotOrientationInputState {

	private RobotOrientationInputState() {
	}

	public static float accumulatedYawDelta;
}
