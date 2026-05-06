package com.natehuntr.infectionmod.network;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record InfectionSyncPayload(boolean infected, String diseaseId, int ticksRemaining, int permanentHeartsLost)
        implements CustomPayload {
    public static final CustomPayload.Id<InfectionSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(InfectionMod.MOD_ID, "infection_sync"));
    public static final PacketCodec<PacketByteBuf, InfectionSyncPayload> CODEC = PacketCodec.of(
        (payload, buf) -> { buf.writeBoolean(payload.infected()); buf.writeString(payload.diseaseId()); buf.writeVarInt(payload.ticksRemaining()); buf.writeVarInt(payload.permanentHeartsLost()); },
        buf -> new InfectionSyncPayload(buf.readBoolean(), buf.readString(), buf.readVarInt(), buf.readVarInt())
    );
    @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
