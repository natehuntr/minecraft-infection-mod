package com.natehuntr.infectionmod.item;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class InfectionItems {

    // Raw meat from an animal that died of Wasting Curse — food-route transmission vector
    public static final Item INFECTED_BEEF = Registry.register(
            Registries.ITEM,
            Identifier.of(InfectionMod.MOD_ID, "infected_beef"),
            new Item(new Item.Settings())
    );

    private InfectionItems() {}

    public static void init() {
        InfectionMod.LOGGER.info("Registered infection mod items");
    }
}
