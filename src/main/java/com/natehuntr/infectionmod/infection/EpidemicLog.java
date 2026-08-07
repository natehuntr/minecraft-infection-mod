package com.natehuntr.infectionmod.infection;

import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory record of every infection and every resolved case, so an outbreak can be
 * measured after the fact rather than inferred from a 50-block snapshot.
 *
 * Cleared on server restart — an epidemic is something you observe within a session.
 */
public final class EpidemicLog {

    public enum Outcome { RECOVERED, DIED }

    /** One transmission. {@code source} is null for index cases (spawn-seeded or /infect). */
    public record Infection(long tick, String diseaseId, UUID victim, String victimName, UUID source) {
        public boolean isIndex() { return source == null; }
    }

    /** An infectious period ending, either in recovery or death. */
    public record Resolution(long tick, String diseaseId, UUID entity, Outcome outcome) {}

    private static final int MAX_EVENTS = 50_000;
    private static final List<Infection> INFECTIONS = new ArrayList<>();
    private static final List<Resolution> RESOLUTIONS = new ArrayList<>();

    private EpidemicLog() {}

    public static void recordInfection(long tick, String diseaseId, LivingEntity victim, LivingEntity source) {
        if (INFECTIONS.size() >= MAX_EVENTS) return;
        INFECTIONS.add(new Infection(tick, diseaseId, victim.getUuid(),
                victim.getName().getString(), source == null ? null : source.getUuid()));
    }

    public static void recordResolution(long tick, String diseaseId, LivingEntity entity, Outcome outcome) {
        if (RESOLUTIONS.size() >= MAX_EVENTS) return;
        RESOLUTIONS.add(new Resolution(tick, diseaseId, entity.getUuid(), outcome));
    }

    public static void clear() {
        INFECTIONS.clear();
        RESOLUTIONS.clear();
    }

    public static int totalRecorded() { return INFECTIONS.size(); }

    public static Set<String> diseasesSeen() {
        Set<String> ids = new LinkedHashSet<>();
        for (Infection i : INFECTIONS) ids.add(i.diseaseId());
        return ids;
    }

    /** Aggregate stats for one disease, or null if it has never been recorded. */
    public static Stats stats(String diseaseId) {
        List<Infection> cases = new ArrayList<>();
        for (Infection i : INFECTIONS) {
            if (i.diseaseId().equals(diseaseId)) cases.add(i);
        }
        if (cases.isEmpty()) return null;

        int indexCases = 0;
        Map<UUID, Integer> secondaryBySource = new HashMap<>();
        for (Infection i : cases) {
            if (i.isIndex()) indexCases++;
            else secondaryBySource.merge(i.source(), 1, Integer::sum);
        }

        // R is only meaningful over cases that have finished spreading, so the
        // denominator is entities whose infectious period has actually resolved.
        // Counting still-infectious cases would drag the mean down every tick.
        Set<UUID> completed = new HashSet<>();
        int recovered = 0, died = 0;
        for (Resolution r : RESOLUTIONS) {
            if (!r.diseaseId().equals(diseaseId)) continue;
            completed.add(r.entity());
            if (r.outcome() == Outcome.DIED) died++; else recovered++;
        }

        int completedSecondary = 0;
        for (UUID u : completed) completedSecondary += secondaryBySource.getOrDefault(u, 0);
        double observedR = completed.isEmpty()
                ? Double.NaN
                : (double) completedSecondary / completed.size();

        int maxSecondary = 0;
        for (int v : secondaryBySource.values()) maxSecondary = Math.max(maxSecondary, v);

        return new Stats(diseaseId, cases.size(), indexCases, cases.size() - indexCases,
                observedR, maxSecondary, completed.size(), recovered, died,
                cases.get(0).tick(), cases.get(cases.size() - 1).tick(),
                Collections.unmodifiableList(cases));
    }

    public record Stats(String diseaseId, int total, int indexCases, int secondaryCases,
                        double observedR, int maxSecondary, int completed,
                        int recovered, int died, long firstTick, long lastTick,
                        List<Infection> cases) {

        private static final int MAX_BUCKETS = 20;

        /** Cases per MC day since the first case, capped so chat output stays readable. */
        public int[] curve() {
            int days = (int) ((lastTick - firstTick) / 24000) + 1;
            int[] buckets = new int[Math.max(1, Math.min(days, MAX_BUCKETS))];
            for (Infection i : cases) {
                int d = (int) ((i.tick() - firstTick) / 24000);
                if (d >= buckets.length) d = buckets.length - 1;   // fold the tail into the last bucket
                if (d >= 0) buckets[d]++;
            }
            return buckets;
        }

        public boolean curveTruncated() {
            return (lastTick - firstTick) / 24000 + 1 > MAX_BUCKETS;
        }
    }
}
