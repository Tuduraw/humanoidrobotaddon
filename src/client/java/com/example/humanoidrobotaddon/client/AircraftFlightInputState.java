package com.example.humanoidrobotaddon.client;

/** Mouse movement accumulated for an aircraft-style (movement.flight_style "aircraft") robot's own FLIGHT
 * body rotation, between client ticks - filled by client.mixin.RobotHeadLookMixin (possibly several
 * mouse events per tick), drained once per tick by HumanoidRobotAddonClient's own orientation-input
 * tick into the same RobotOrientationInputPayload the arrow keys use. Degrees, the same scale and
 * sign the base mod's own AircraftOrientationInputState uses for its aircraft. */
public final class AircraftFlightInputState {

	private AircraftFlightInputState() {
	}

	public static float yawDelta;
	public static float pitchDelta;
}
