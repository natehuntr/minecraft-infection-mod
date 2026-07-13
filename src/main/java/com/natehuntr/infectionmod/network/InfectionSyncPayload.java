package com.natehuntr.infectionmod.network;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.List;

public record InfectionSyncPayload(boolean infected, boolean exposed, String diseaseId,
                                   int incubationTicksRemaining, int ticksRemaining,
                                   int permanentHeartsLost, List<String> symptomIds,
                                   int symptomTicksRemaining)
        implements CustomPayload {
    public static final CustomPayload.Id<InfectionSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(InfectionMod.MOD_ID, "infection_sync"));
    public static final PacketCodec<PacketByteBuf, InfectionSyncPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeBoolean(payload.infected());
                buf.writeBoolean(payload.exposed());
                buf.writeString(payload.diseaseId());
                buf.writeVarInt(payload.incubationTicksRemaining());
                buf.writeVarInt(payload.ticksRemaining());
                buf.writeVarInt(payload.permanentHeartsLost());
                buf.writeVarInt(payload.symptomIds().size());
                for (String id : payload.symptomIds()) buf.writeString(id);
                buf.writeVarInt(payload.symptomTicksRemaining());
            },
            buf -> {
                boolean infected = buf.readBoolean();
                boolean exposed = buf.readBoolean();
                String diseaseId = buf.readString();
                int incubationTicksRemaining = buf.readVarInt();
                int ticksRemaining = buf.readVarInt();
                int permanentHeartsLost = buf.readVarInt();
                int count = buf.readVarInt();
                List<String> symptomIds = new ArrayList<>(count);
                for (int i = 0; i < count; i++) symptomIds.add(buf.readString());
                int symptomTicksRemaining = buf.readVarInt();
                return new InfectionSyncPayload(infected, exposed, diseaseId, incubationTicksRemaining,
                        ticksRemaining, permanentHeartsLost, symptomIds, symptomTicksRemaining);
            }
    );
    @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
