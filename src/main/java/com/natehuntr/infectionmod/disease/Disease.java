package com.natehuntr.infectionmod.disease;

public record Disease(
        String id,
        String displayName,
        float baseTransmissionRate,
        float severity,
        int incubationTicks,
        int durationTicks,
        float postRecoveryImmunity,
        int immunityDurationTicks
) {}
