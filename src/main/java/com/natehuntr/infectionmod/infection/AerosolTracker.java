package com.natehuntr.infectionmod.infection;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Infectious aerosol left behind in blocks by contagious hosts, so a room stays dangerous
 * after the host has walked away — the defining transmission route for measles, which
 * remains airborne for roughly two hours.
 *
 * Sources write one block per second (where they stand); targets read the 3x3x3 around
 * themselves. Inverting the cost this way keeps writes cheap and makes reads plain hash
 * lookups, rather than having each source paint a volume it may immediately leave.
 *
 * Held per dimension and cleared on server stop: block positions are only meaningful within
 * a world, and stale entries would otherwise infect people in the next world loaded.
 */
public final class AerosolTracker {

    /** One contaminated block. Freshness decays linearly from deposit to expiry. */
    private record Cloud(String diseaseId, UUID source, long depositedTick, long expiresTick) {}

    /** What a target found in the air around it. */
    public record Hit(String diseaseId, UUID source, float freshness) {}

    // Bounds worst-case memory: a long-running server with many infected hosts would
    // otherwise accumulate a block entry per host per second until they expire.
    private static final int MAX_PER_WORLD = 20_000;

    private static final Map<RegistryKey<World>, Map<BlockPos, Cloud>> WORLDS = new HashMap<>();

    private AerosolTracker() {}

    public static void deposit(ServerWorld world, BlockPos pos, String diseaseId,
                               UUID source, int lifetimeSeconds) {
        if (lifetimeSeconds <= 0 || diseaseId == null) return;
        Map<BlockPos, Cloud> clouds = WORLDS.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>());

        BlockPos key = pos.toImmutable();   // callers may hand us a Mutable being reused
        if (clouds.size() >= MAX_PER_WORLD && !clouds.containsKey(key)) return;

        long now = world.getTime();
        // Re-depositing refreshes rather than stacking: a host loitering in one place keeps
        // that block at full strength, which is the intended behaviour.
        clouds.put(key, new Cloud(diseaseId, source, now, now + lifetimeSeconds * 20L));
    }

    /** Freshest contamination in the 3x3x3 centred on {@code centre}, or null if clean. */
    public static Hit sample(ServerWorld world, BlockPos centre) {
        Map<BlockPos, Cloud> clouds = WORLDS.get(world.getRegistryKey());
        if (clouds == null || clouds.isEmpty()) return null;

        long now = world.getTime();
        Hit best = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Cloud c = clouds.get(new BlockPos(
                            centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz));
                    if (c == null || c.expiresTick() <= now) continue;

                    float span = c.expiresTick() - c.depositedTick();
                    float freshness = span <= 0 ? 0f : (c.expiresTick() - now) / span;
                    if (freshness <= 0f) continue;
                    if (best == null || freshness > best.freshness()) {
                        best = new Hit(c.diseaseId(), c.source(), freshness);
                    }
                }
            }
        }
        return best;
    }

    /** Drops expired clouds. Called once a second per world. */
    public static void tick(ServerWorld world) {
        Map<BlockPos, Cloud> clouds = WORLDS.get(world.getRegistryKey());
        if (clouds == null || clouds.isEmpty()) return;
        long now = world.getTime();
        clouds.values().removeIf(c -> c.expiresTick() <= now);
    }

    /** Contaminated block count, for /infection-stats. */
    public static int size(ServerWorld world) {
        Map<BlockPos, Cloud> clouds = WORLDS.get(world.getRegistryKey());
        return clouds == null ? 0 : clouds.size();
    }

    public static void clear() { WORLDS.clear(); }
}
