package com.example.humanoidrobotaddon.network;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: the mode key was pressed (short or long). Carries only the kind of press;
 * the server resolves it against the robot's current mode (HumanoidRobotEntity.applyModeKey()),
 * so a client can't name an arbitrary mode. */
public record SetMovementModePayload(boolean longPress) implements CustomPayload {

	public static final CustomPayload.Id<SetMovementModePayload> ID =
			new CustomPayload.Id<>(Identifier.of(HumanoidRobotAddon.MOD_ID, "set_movement_mode"));

	public static final PacketCodec<RegistryByteBuf, SetMovementModePayload> CODEC = PacketCodec.tuple(
			PacketCodecs.BOOLEAN, SetMovementModePayload::longPress,
			SetMovementModePayload::new
	);

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
