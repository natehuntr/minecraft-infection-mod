package com.natehuntr.infectionmod.disease;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.entity.EntityType;
import java.util.*;

public final class DiseaseRegistry {
    private static final Map<String, Disease> REGISTRY = new LinkedHashMap<>();
    private static final Map<String, Set<EntityType<?>>> RESERVOIR_HOST_MAP = new LinkedHashMap<>();

    // R₀ 2.5 — COVID-like; exposure curve τ=60s, max 80% cumulative; broad reservoir; 5% spawn
    public static final Disease CRIMSON_FEVER = register(
            new Disease("crimson_fever", "Crimson Fever", 0.80f, 0.05f, 72000, 120000, 0.01f, 72000, 60),
            Set.of(EntityType.BAT, EntityType.PIG, EntityType.COW, EntityType.CHICKEN,
                   EntityType.SHEEP, EntityType.FOX, EntityType.WOLF, EntityType.CAT,
                   EntityType.VILLAGER, EntityType.HORSE, EntityType.DONKEY, EntityType.MULE,
                   EntityType.RABBIT)
    );

    // R₀ 12 — Measles-like; exposure curve τ=20s, max 99% cumulative; villagers only; 5% spawn
    public static final Disease SCARLET_BLIGHT = register(
            new Disease("scarlet_blight", "Scarlet Blight", 0.99f, 0.05f, 120000, 168000, 0.002f, 2400000, 20),
            Set.of(EntityType.VILLAGER)
    );

    // Prion/CJD-like; flat 0.1%/sec airborne (τ=0 disables curve); cattle only; 95% CFR; 1% spawn
    public static final Disease WASTING_CURSE = register(
            new Disease("wasting_curse", "Wasting Curse", 0.001f, 0.01f, 120000, 240000, 0.95f, 0, 0),
            Set.of(EntityType.COW)
    );

    private DiseaseRegistry() {}

    private static Disease register(Disease disease, Set<EntityType<?>> hosts) {
        REGISTRY.put(disease.id(), disease);
        RESERVOIR_HOST_MAP.put(disease.id(), Collections.unmodifiableSet(new HashSet<>(hosts)));
        return disease;
    }

    public static Disease get(String id) { return REGISTRY.get(id); }
    public static Collection<Disease> getAll() { return Collections.unmodifiableCollection(REGISTRY.values()); }

    public static Set<EntityType<?>> getReservoirHosts(String diseaseId) {
        return RESERVOIR_HOST_MAP.getOrDefault(diseaseId, Set.of());
    }

    public static Set<EntityType<?>> getAllReservoirHosts() {
        Set<EntityType<?>> all = new HashSet<>();
        RESERVOIR_HOST_MAP.values().forEach(all::addAll);
        return Collections.unmodifiableSet(all);
    }

    public static List<Disease> getDiseasesForHost(EntityType<?> type) {
        List<Disease> result = new ArrayList<>();
        for (Map.Entry<String, Set<EntityType<?>>> entry : RESERVOIR_HOST_MAP.entrySet()) {
            if (entry.getValue().contains(type)) {
                Disease d = REGISTRY.get(entry.getKey());
                if (d != null) result.add(d);
            }
        }
        return result;
    }

    public static void init() {
        InfectionMod.LOGGER.info("Registered {} disease(s): {}", REGISTRY.size(), REGISTRY.keySet());
    }
}
