package com.example.humanoidrobotaddon.client;

/** The local pilot's head-look offset in FLIGHT (flight_style "robot"): how far the view points
 * away from the robot's nose, independent of the body. Written by RobotHeadLookMixin, read every
 * frame by RobotCameraMixin, and mirrored to the server (RobotHeadLookPayload) for weapon-aim. Two
 * independent scalars, rebuilt as Ry(yaw)*Rx(pitch) each frame - a quaternion accumulated by local
 * rotations drifts into roll. Client-only, purely a rendering/aim-direction concern. */
public final class HeadLookOffsetState {

	private HeadLookOffsetState() {
	}

	public static float yaw;
	public static float pitch;
}
