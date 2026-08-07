package com.natehuntr.infectionmod.network;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity IDs of nearby villagers currently showing the Scarlet Blight rash.
 *
 * Sent once a second as a full replacement list rather than add/remove deltas: it is a
 * handful of varints, and a self-correcting snapshot cannot drift out of sync if a packet
 * is missed or an entity is untracked and retracked.
 */
public record VillagerInfectionPayload(List<Integer> entityIds) implements CustomPayload {

    public static final CustomPayload.Id<VillagerInfectionPayload> ID =
            new CustomPayload.Id<>(Identifier.of(InfectionMod.MOD_ID, "villager_infection"));

    public static final PacketCodec<PacketByteBuf, VillagerInfectionPayload> CODEC = PacketCodec.of(
            (payload, buf) -> {
                buf.writeVarInt(payload.entityIds().size());
                for (int id : payload.entityIds()) buf.writeVarInt(id);
            },
            buf -> {
                int count = buf.readVarInt();
                List<Integer> ids = new ArrayList<>(count);
                for (int i = 0; i < count; i++) ids.add(buf.readVarInt());
                return new VillagerInfectionPayload(ids);
            }
    );

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
