package com.natehuntr.infectionmod.client;

import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import com.natehuntr.infectionmod.network.VillagerInfectionPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.entity.EntityType;

@Environment(EnvType.CLIENT)
public class InfectionModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(InfectionSyncPayload.ID,
                (payload, context) -> context.client().execute(() -> {
                    InfectionHudOverlay.infected = payload.infected();
                    InfectionHudOverlay.exposed = payload.exposed();
                    InfectionHudOverlay.diseaseId = payload.diseaseId();
                    InfectionHudOverlay.incubationTicksRemaining = payload.incubationTicksRemaining();
                    InfectionHudOverlay.ticksRemaining = payload.ticksRemaining();
                    InfectionHudOverlay.permanentHeartsLost = payload.permanentHeartsLost();
                    InfectionHudOverlay.symptomIds = payload.symptomIds();
                    InfectionHudOverlay.symptomTicksRemaining = payload.symptomTicksRemaining();
                })
        );

        ClientPlayNetworking.registerGlobalReceiver(VillagerInfectionPayload.ID,
                (payload, context) -> context.client().execute(() ->
                        InfectedVillagerTracker.update(payload.entityIds()))
        );

        // Entity IDs are only unique within a connection, so stale ones would otherwise
        // mark unrelated entities as diseased in the next world joined.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                InfectedVillagerTracker.clear());

        // Replaces the vanilla villager renderer so infected villagers can swap texture.
        EntityRendererRegistry.register(EntityType.VILLAGER, InfectedVillagerRenderer::new);

        HudRenderCallback.EVENT.register(InfectionHudOverlay::render);
    }
}
