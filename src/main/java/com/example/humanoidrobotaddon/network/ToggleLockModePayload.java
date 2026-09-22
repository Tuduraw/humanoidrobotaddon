package com.example.humanoidrobotaddon.network;

import com.example.humanoidrobotaddon.HumanoidRobotAddon;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Client -> server: the pilot pressed the lock-mode key - switch the SELECTED weapon between
 * multi-target and single-target (focus) mode, if it is a multi_lock weapon (see HumanoidRobotEntity's
 * own tudursvehiclemod$toggleLockFocus()). No fields: the server resolves which weapon from its own
 * synced weapon selection. */
public record ToggleLockModePayload() implements CustomPayload {

	public static final CustomPayload.Id<ToggleLockModePayload> ID =
			new CustomPayload.Id<>(Identifier.of(HumanoidRobotAddon.MOD_ID, "toggle_lock_mode"));

	public static final PacketCodec<RegistryByteBuf, ToggleLockModePayload> CODEC =
			PacketCodec.unit(new ToggleLockModePayload());

	@Override
	public CustomPayload.Id<? extends CustomPayload> getId() {
		return ID;
	}
}
