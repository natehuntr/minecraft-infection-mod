package com.natehuntr.infectionmod.disease;

public record Disease(
        String id,
        String displayName,
        float baseTransmissionRate,
        float spawnInfectionChance,
        int incubationTicks,
        int durationTicks,
        float caseFatalityRate,
        int immunityDurationTicks
) {}
