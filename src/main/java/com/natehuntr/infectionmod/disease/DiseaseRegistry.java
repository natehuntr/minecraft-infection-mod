package com.natehuntr.infectionmod.disease;

import com.natehuntr.infectionmod.InfectionMod;
import java.util.*;

public final class DiseaseRegistry {
    private static final Map<String, Disease> REGISTRY = new LinkedHashMap<>();

    public static final Disease CRIMSON_FEVER = register(new Disease(
            "crimson_fever", "Crimson Fever",
            0.30f, 0.5f, 0, 12000, 0.80f, 120000
    ));

    private DiseaseRegistry() {}
    private static Disease register(Disease disease) { REGISTRY.put(disease.id(), disease); return disease; }
    public static Disease get(String id) { return REGISTRY.get(id); }
    public static Collection<Disease> getAll() { return Collections.unmodifiableCollection(REGISTRY.values()); }
    public static void init() { InfectionMod.LOGGER.info("Registered {} disease(s): {}", REGISTRY.size(), REGISTRY.keySet()); }
}
