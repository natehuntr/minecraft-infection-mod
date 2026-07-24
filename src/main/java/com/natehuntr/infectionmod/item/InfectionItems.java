package com.natehuntr.infectionmod.item;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public final class InfectionItems {

    private static final RegistryKey<Item> INFECTED_BEEF_KEY =
            RegistryKey.of(RegistryKeys.ITEM, Identifier.of(InfectionMod.MOD_ID, "infected_beef"));

    // Raw meat from an animal that died of Wasting Curse — food-route transmission vector
    public static final Item INFECTED_BEEF = Registry.register(
            Registries.ITEM,
            INFECTED_BEEF_KEY,
            new Item(new Item.Settings().registryKey(INFECTED_BEEF_KEY))
    );

    private InfectionItems() {}

    public static void init() {
        InfectionMod.LOGGER.info("Registered infection mod items");
    }
}
