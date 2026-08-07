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
        /**
         * Leading portion of durationTicks during which the host is contagious but shows no
         * visible sign — measles is infectious for roughly four days before the rash appears,
         * which is the main reason it spreads as well as it does. 0 means visible immediately.
         */
        int prodromeTicks,
        float caseFatalityRate,
        int immunityDurationTicks,
        int exposureHalfLifeSeconds,
        /**
         * How long exhaled aerosol stays infectious in a block after the host leaves, in
         * seconds. 0 disables lingering transmission entirely (contact-only).
         */
        int aerosolLifetimeSeconds
) {
    /** Ticks into the infectious period at which the visible sign appears. */
    public int rashOnsetTicks() { return Math.max(0, durationTicks - prodromeTicks); }
}
