package dev.novab.autismtv.network;

import dev.novab.autismtv.AutismTVMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class AutismTVPayloads {
    public static final Identifier C2S_CHANNEL = Identifier.of(AutismTVMod.MOD_ID, "relay_c2s");
    public static final Identifier S2C_CHANNEL = Identifier.of(AutismTVMod.MOD_ID, "relay_s2c");

    private static boolean registered;

    private AutismTVPayloads() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        registered = true;
        PayloadTypeRegistry.playC2S().register(ClientToServerPayload.ID, ClientToServerPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerToClientPayload.ID, ServerToClientPayload.CODEC);
    }

    public record ClientToServerPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<ClientToServerPayload> ID = new CustomPayload.Id<>(C2S_CHANNEL);
        public static final PacketCodec<RegistryByteBuf, ClientToServerPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.BYTE_ARRAY,
                ClientToServerPayload::data,
                ClientToServerPayload::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record ServerToClientPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<ServerToClientPayload> ID = new CustomPayload.Id<>(S2C_CHANNEL);
        public static final PacketCodec<RegistryByteBuf, ServerToClientPayload> CODEC = PacketCodec.tuple(
                PacketCodecs.BYTE_ARRAY,
                ServerToClientPayload::data,
                ServerToClientPayload::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}