package com.natehuntr.infectionmod;

import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InfectionMod implements ModInitializer {
    public static final String MOD_ID = "infection_mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        DiseaseRegistry.init();
        LOGGER.info("Infection Mod initialized");
    }
}
