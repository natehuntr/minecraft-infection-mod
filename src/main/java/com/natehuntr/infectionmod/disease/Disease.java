package com.natehuntr.infectionmod.disease;

public record Disease(
        String id,
        String displayName,
        // For curve diseases (exposureHalfLifeSeconds > 0): asymptotic max cumulative P.
        // For flat diseases (exposureHalfLifeSeconds == 0): per-second transmission probability.
        float maxTransmissionRate,
        float spawnInfectionChance,
        int incubationTicks,
        int durationTicks,
        float caseFatalityRate,
        int immunityDurationTicks,
        int exposureHalfLifeSeconds
) {}
