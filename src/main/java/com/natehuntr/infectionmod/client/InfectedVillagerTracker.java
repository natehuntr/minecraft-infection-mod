package com.natehuntr.infectionmod.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Client-side view of which villagers are visibly diseased, kept in sync by
 * {@link com.natehuntr.infectionmod.network.VillagerInfectionPayload}.
 *
 * Read during rendering and written from the netty packet handler, so both sides are
 * marshalled onto the client thread by the receiver's {@code execute(...)}.
 */
@Environment(EnvType.CLIENT)
public final class InfectedVillagerTracker {

    private static final Set<Integer> INFECTED = new HashSet<>();

    private InfectedVillagerTracker() {}

    /** Replaces the whole set — the payload is a snapshot, not a delta. */
    public static void update(Collection<Integer> entityIds) {
        INFECTED.clear();
        INFECTED.addAll(entityIds);
    }

    public static boolean isInfected(int entityId) {
        return INFECTED.contains(entityId);
    }

    public static void clear() {
        INFECTED.clear();
    }
}
