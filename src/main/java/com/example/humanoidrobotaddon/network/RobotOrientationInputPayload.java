package com.example.humanoidrobotaddon.network;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: this tick's FLIGHT yaw/pitch input (arrow keys, plus the mouse for
 * flight_style "aircraft"). Roll rides the base mod's synced sideways input. Sent as a magnitude;
 * the receiver clamps to the configured per-tick rate, so held keys simply saturate it. */
public record RobotOrientationInputPayload(float yawDelta, float pitchDelta) implements CustomPayload {

	public static final CustomPayload.Id<RobotOrientationInputPayload> ID =
			new CustomPayload.Id<>(Identifier.of(HumanoidRobotAddon.MOD_ID, "orientation_input"));

	public static final PacketCodec<RegistryByteBuf, RobotOrientationInputPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.FLOAT, RobotOrientationInputPayload::yawDelta,
			PacketCodecs.FLOAT, RobotOrientationInputPayload::pitchDelta,
			RobotOrientationInputPayload::new
	);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
