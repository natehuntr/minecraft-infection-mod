package com.natehuntr.infectionmod;

import com.natehuntr.infectionmod.command.InfectionCommand;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.infection.InfectionAttachments;
import com.natehuntr.infectionmod.infection.InfectionManager;
import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InfectionMod implements ModInitializer {
    public static final String MOD_ID = "infection_mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        DiseaseRegistry.init();
        InfectionAttachments.init();
        PayloadTypeRegistry.playS2C().register(InfectionSyncPayload.ID, InfectionSyncPayload.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                InfectionCommand.register(dispatcher));
        ServerTickEvents.END_WORLD_TICK.register(InfectionManager::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> InfectionManager.reapplyOnLogin(handler.player));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> InfectionManager.handleRespawn(newPlayer, !alive));
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> InfectionManager.onEntityLoad(entity, world));
        LOGGER.info("Infection Mod initialized");
    }
}