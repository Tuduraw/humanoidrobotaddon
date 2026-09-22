package com.example.humanoidrobotaddon.network;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server, every FLIGHT tick: the pilot's head-look offset (HeadLookOffsetState), so
 * candidate search can use the camera's actual direction - the head-look never touches the pilot's
 * stored yaw/pitch, so getRotationVec() would be unrelated to what the camera shows. */
public record RobotHeadLookPayload(float yaw, float pitch) implements CustomPayload {

	public static final CustomPayload.Id<RobotHeadLookPayload> ID =
			new CustomPayload.Id<>(Identifier.of(HumanoidRobotAddon.MOD_ID, "head_look"));

	public static final PacketCodec<RegistryByteBuf, RobotHeadLookPayload> CODEC = PacketCodec.tuple(
			PacketCodecs.FLOAT, RobotHeadLookPayload::yaw,
			PacketCodecs.FLOAT, RobotHeadLookPayload::pitch,
			RobotHeadLookPayload::new
	);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
