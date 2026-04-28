package com.natehuntr.infectionmod.client;

import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

@Environment(EnvType.CLIENT)
public class InfectionModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(InfectionSyncPayload.ID,
            (payload, context) -> context.client().execute(() -> {
                InfectionHudOverlay.infected = payload.infected();
                InfectionHudOverlay.diseaseId = payload.diseaseId();
                InfectionHudOverlay.ticksRemaining = payload.ticksRemaining();
            })
        );
        HudRenderCallback.EVENT.register(InfectionHudOverlay::render);
    }
}
