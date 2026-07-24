package com.natehuntr.infectionmod.infection;

import com.natehuntr.infectionmod.InfectionMod;
import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.item.InfectionItems;
import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InfectionManager {
    private static final double PROXIMITY_RADIUS = 3.0;
    private static final double PROXIMITY_RADIUS_MEDIUM = 6.0;
    private static final int SYMPTOM_TICKS_MIN = 3600;
    private static final int SYMPTOM_TICKS_MAX = 24000;
    private static final List<String> SYMPTOM_IDS = List.of("slowness", "nausea", "weakness");
    private static final Identifier TEMP_HEALTH_ID =
            Identifier.of(InfectionMod.MOD_ID, "infection_health_penalty");

    // Tracks non-player infected entities so they can be ticked each second
    private static final Set<UUID> infectedAnimalUUIDs = new HashSet<>();

    // UUIDs of animals currently being killed by the disease (not by a player).
    // Used to suppress the duplicate Infected Beef drop in onAnimalDeath.
    private static final Set<UUID> pendingDiseaseDeath = new HashSet<>();

    // Accumulated effective exposure seconds per (source UUID : target UUID) pair.
    // Increments each second entities are in range (rate varies by proximity);
    // decays at 2× the base rate per second when out of range.
    private static final Map<String, Float> exposureCounters = new HashMap<>();

    private InfectionManager() {}

    public static void tick(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) tickPlayer(world, player);
        if (world.getTime() % 20 == 0) {
            spreadDisease(world);
            tickAnimals(world);
        }
    }

    // -------------------------------------------------------------------------
    // Player ticking
    // -------------------------------------------------------------------------

    private static void tickPlayer(ServerWorld world, ServerPlayerEntity player) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);

        if (state.isExposed()) {
            state.tickIncubation();
            if (state.getIncubationTicksRemaining() <= 0) {
                ensureHealthPenalty(player);
                rollSymptoms(world, state);
                syncToClient(player, state);
            } else if (world.getTime() % 20 == 0) {
                syncToClient(player, state);
            }
        } else if (state.isInfectious()) {
            state.tickInfection();
            state.tickSymptoms();
            if (state.getTicksRemaining() <= 0) {
                recoverPlayer(world, player, state);
            } else {
                ensureHealthPenalty(player);
                if (world.getTime() % 20 == 0) {
                    applySymptomEffects(player, state);
                    syncToClient(player, state);
                }
            }
        } else if (state.isImmune()) {
            state.tickImmunity();
            if (state.getImmunityTicksRemaining() <= 0) {
                state.clearImmunity();
                syncToClient(player, state);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Animal ticking — called once per second (every 20 game ticks)
    // -------------------------------------------------------------------------

    private static void tickAnimals(ServerWorld world) {
        Iterator<UUID> iter = infectedAnimalUUIDs.iterator();
        while (iter.hasNext()) {
            UUID uuid = iter.next();
            Entity entity = world.getEntity(uuid);
            if (!(entity instanceof LivingEntity living) || living.isRemoved()) {
                iter.remove();
                continue;
            }
            if (living instanceof ServerPlayerEntity) {
                iter.remove();
                continue;
            }
            InfectionState state = living.getAttached(InfectionAttachments.INFECTION);
            if (state == null || !state.isInfected()) {
                iter.remove();
                continue;
            }
            tickAnimal(world, living, state, iter);
        }
    }

    private static void tickAnimal(ServerWorld world, LivingEntity animal,
                                   InfectionState state, Iterator<UUID> iter) {
        if (state.isExposed()) {
            // Advance incubation by one second (20 ticks) per call
            state.tickIncubation(20);
        } else if (state.isInfectious()) {
            state.tickInfection(20);
            if (state.getTicksRemaining() <= 0) {
                recoverAnimal(world, animal, state);
                iter.remove();
            }
        }
    }

    private static void recoverAnimal(ServerWorld world, LivingEntity animal, InfectionState state) {
        Disease disease = DiseaseRegistry.get(state.getDiseaseId());
        float cfr = disease != null ? disease.caseFatalityRate() : 0f;

        if (cfr > 0 && world.getRandom().nextFloat() < cfr) {
            // Mark as disease-kill so onAnimalDeath doesn't double-drop infected meat.
            // damage() fires death events synchronously, so remove immediately after.
            pendingDiseaseDeath.add(animal.getUuid());
            dropInfectedMeat(world, animal, state.getDiseaseId());
            animal.damage(world, world.getDamageSources().generic(), Float.MAX_VALUE);
            pendingDiseaseDeath.remove(animal.getUuid());
        } else {
            state.recover(disease != null ? disease.immunityDurationTicks() : 0);
        }
    }

    private static void dropInfectedMeat(ServerWorld world, LivingEntity animal, String diseaseId) {
        if (!"wasting_curse".equals(diseaseId)) return;
        if (animal.getType() != EntityType.COW && animal.getType() != EntityType.MOOSHROOM) return;
        ItemStack stack = new ItemStack(InfectionItems.INFECTED_BEEF);
        ItemEntity drop = new ItemEntity(world, animal.getX(), animal.getY() + 0.5, animal.getZ(), stack);
        drop.setVelocity(0, 0.2, 0);
        world.spawnEntity(drop);
    }

    // -------------------------------------------------------------------------
    // Spread
    // -------------------------------------------------------------------------

    private static void spreadDisease(ServerWorld world) {
        Set<LivingEntity> sources = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            world.getEntitiesByClass(LivingEntity.class,
                    player.getBoundingBox().expand(PROXIMITY_RADIUS_MEDIUM + 8),
                    e -> isInfectious(e)
            ).forEach(sources::add);
        }

        Set<String> activeThisSecond = new HashSet<>();

        for (LivingEntity source : sources) {
            InfectionState srcState = source.getAttached(InfectionAttachments.INFECTION);
            if (srcState == null) continue;
            Disease disease = DiseaseRegistry.get(srcState.getDiseaseId());
            if (disease == null) continue;

            List<LivingEntity> targets = world.getEntitiesByClass(LivingEntity.class,
                    source.getBoundingBox().expand(PROXIMITY_RADIUS_MEDIUM),
                    t -> t != source
                            && isTargetSusceptibleTo(t, disease)
                            && !isInfected(t)
                            && !isImmune(t)
            );
            for (LivingEntity target : targets) {
                boolean contact = source.getBoundingBox().intersects(target.getBoundingBox());
                boolean closeRange = source.getBoundingBox().expand(PROXIMITY_RADIUS)
                        .intersects(target.getBoundingBox());

                // Accumulation rate: contact counts double (halves effective τ),
                // medium range counts at 1/6 (lengthens effective τ sixfold).
                float rate = contact ? 2.0f : closeRange ? 1.0f : 1.0f / 6.0f;

                String key = source.getUuidAsString() + ":" + target.getUuidAsString();
                float prevAcc = exposureCounters.getOrDefault(key, 0.0f);
                float newAcc = prevAcc + rate;
                exposureCounters.put(key, newAcc);
                activeThisSecond.add(key);

                float chance = transmissionChance(disease, prevAcc, newAcc, rate);
                if (world.getRandom().nextFloat() < chance) {
                    infect(target, disease);
                    exposureCounters.remove(key);
                }
            }
        }

        // Decay counters for pairs not in range this second (2× base rate per second)
        exposureCounters.entrySet().removeIf(entry -> {
            if (activeThisSecond.contains(entry.getKey())) return false;
            float decayed = entry.getValue() - 2.0f;
            if (decayed <= 0) return true;
            entry.setValue(decayed);
            return false;
        });
    }

    private static float transmissionChance(Disease disease, float prevAcc, float newAcc, float rate) {
        int tau = disease.exposureHalfLifeSeconds();
        if (tau <= 0) {
            // Flat rate — duration irrelevant (prion model)
            return disease.maxTransmissionRate() * rate;
        }
        // Marginal probability of THIS second's exposure causing infection:
        // P(newAcc) - P(prevAcc) where P(t) = maxP × (1 - e^(-t/τ))
        float maxP = disease.maxTransmissionRate();
        return maxP * (float)(Math.exp(-prevAcc / tau) - Math.exp(-newAcc / tau));
    }

    // -------------------------------------------------------------------------
    // Infection entry point
    // -------------------------------------------------------------------------

    public static void infect(LivingEntity entity, Disease disease) {
        InfectionState state = entity.getAttachedOrCreate(InfectionAttachments.INFECTION);
        state.infect(disease.id(), disease.incubationTicks(), disease.durationTicks());
        if (entity instanceof ServerPlayerEntity player) {
            if (disease.incubationTicks() <= 0) {
                ensureHealthPenalty(player);
                rollSymptoms(player.getServerWorld(), state);
            }
            syncToClient(player, state);
        } else {
            infectedAnimalUUIDs.add(entity.getUuid());
        }
    }

    // -------------------------------------------------------------------------
    // Player recovery
    // -------------------------------------------------------------------------

    private static void recoverPlayer(ServerWorld world, ServerPlayerEntity player, InfectionState state) {
        Disease disease = DiseaseRegistry.get(state.getDiseaseId());
        float cfr = disease != null ? disease.caseFatalityRate() : 0f;

        removeHealthPenalty(player);
        for (String id : state.getActiveSymptomIds()) {
            RegistryEntry<StatusEffect> effect = effectForId(id);
            if (effect != null) player.removeStatusEffect(effect);
        }

        if (cfr > 0 && world.getRandom().nextFloat() < cfr) {
            state.clearInfection();
            syncToClient(player, state);
            player.damage(world, world.getDamageSources().generic(), Float.MAX_VALUE);
            return;
        }

        double maxHealth = player.getAttributeValue(EntityAttributes.MAX_HEALTH);
        if (world.getRandom().nextFloat() < state.permanentLossChance() && maxHealth - 2.0 >= 8.0) {
            state.recordPermanentHeartLoss();
            applyPermanentLoss(player, state.getPermanentHeartsLost());
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        state.recover(disease != null ? disease.immunityDurationTicks() : 0);
        syncToClient(player, state);
    }

    // -------------------------------------------------------------------------
    // Symptoms
    // -------------------------------------------------------------------------

    private static void rollSymptoms(ServerWorld world, InfectionState state) {
        float roll = world.getRandom().nextFloat();
        int count = roll < 0.01f ? 2 : roll < 0.31f ? 1 : 0;
        if (count == 0) return;

        List<String> pool = new ArrayList<>(SYMPTOM_IDS);
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            chosen.add(pool.remove(world.getRandom().nextInt(pool.size())));
        }
        int duration = SYMPTOM_TICKS_MIN + world.getRandom().nextInt(SYMPTOM_TICKS_MAX - SYMPTOM_TICKS_MIN + 1);
        state.setSymptoms(chosen, duration);
    }

    private static void applySymptomEffects(ServerPlayerEntity player, InfectionState state) {
        if (!state.hasSymptoms()) return;
        int ticks = state.getSymptomTicksRemaining();
        for (String id : state.getActiveSymptomIds()) {
            RegistryEntry<StatusEffect> effect = effectForId(id);
            if (effect == null) continue;
            int amp = "slowness".equals(id) ? 1 : 0;
            player.addStatusEffect(new StatusEffectInstance(effect, ticks, amp, false, true));
        }
    }

    private static RegistryEntry<StatusEffect> effectForId(String id) {
        return switch (id) {
            case "slowness" -> StatusEffects.SLOWNESS;
            case "nausea"   -> StatusEffects.NAUSEA;
            case "weakness" -> StatusEffects.WEAKNESS;
            default         -> null;
        };
    }

    // -------------------------------------------------------------------------
    // Health modifiers
    // -------------------------------------------------------------------------

    private static void ensureHealthPenalty(ServerPlayerEntity player) {
        var attr = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (attr != null && attr.getModifier(TEMP_HEALTH_ID) == null) {
            attr.addTemporaryModifier(new EntityAttributeModifier(TEMP_HEALTH_ID, -4.0, EntityAttributeModifier.Operation.ADD_VALUE));
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
    }

    private static void removeHealthPenalty(ServerPlayerEntity player) {
        var attr = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (attr != null) attr.removeModifier(TEMP_HEALTH_ID);
    }

    public static void applyPermanentLoss(ServerPlayerEntity player, int lossCount) {
        var attr = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
        if (attr == null) return;
        for (int i = 0; i < lossCount; i++) {
            Identifier id = Identifier.of(InfectionMod.MOD_ID, "perm_loss_" + i);
            if (attr.getModifier(id) == null)
                attr.addPersistentModifier(new EntityAttributeModifier(id, -2.0, EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    // -------------------------------------------------------------------------
    // Login / respawn
    // -------------------------------------------------------------------------

    public static void reapplyOnLogin(ServerPlayerEntity player) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (state.isInfectious()) {
            ensureHealthPenalty(player);
            applySymptomEffects(player, state);
        }
        applyPermanentLoss(player, state.getPermanentHeartsLost());
        syncToClient(player, state);
    }

    public static void handleRespawn(ServerPlayerEntity player, boolean fromDeath) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (fromDeath) state.clearInfection();
        applyPermanentLoss(player, state.getPermanentHeartsLost());
        syncToClient(player, state);
    }

    // -------------------------------------------------------------------------
    // Networking
    // -------------------------------------------------------------------------

    public static void syncToClient(ServerPlayerEntity player, InfectionState state) {
        ServerPlayNetworking.send(player, new InfectionSyncPayload(
                state.isInfected(),
                state.isExposed(),
                state.getDiseaseId() != null ? state.getDiseaseId() : "",
                state.getIncubationTicksRemaining(),
                state.getTicksRemaining(),
                state.getPermanentHeartsLost(),
                state.getActiveSymptomIds(),
                state.getSymptomTicksRemaining()
        ));
    }

    // -------------------------------------------------------------------------
    // Entity load / spawn infection
    // -------------------------------------------------------------------------

    public static void onEntityLoad(Entity entity, ServerWorld world) {
        if (!(entity instanceof LivingEntity living)) return;
        if (living instanceof ServerPlayerEntity) return;
        List<Disease> hostDiseases = DiseaseRegistry.getDiseasesForHost(living.getType());
        if (hostDiseases.isEmpty()) return;

        // Re-register animals that already have a persisted infection state so their
        // disease progression resumes after a chunk reload or server restart.
        InfectionState existing = living.getAttached(InfectionAttachments.INFECTION);
        if (existing != null) {
            if (existing.isInfected()) infectedAnimalUUIDs.add(living.getUuid());
            return;
        }

        living.getAttachedOrCreate(InfectionAttachments.INFECTION);
        for (Disease disease : hostDiseases) {
            if (world.getRandom().nextFloat() < disease.spawnInfectionChance()) {
                infect(living, disease);
                break;
            }
        }
    }

    public static void onAnimalDeath(LivingEntity entity, ServerWorld world) {
        if (pendingDiseaseDeath.contains(entity.getUuid())) return;
        InfectionState state = entity.getAttached(InfectionAttachments.INFECTION);
        if (state == null || !state.isInfectious()) return;
        dropInfectedMeat(world, entity, state.getDiseaseId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public static boolean isSusceptible(LivingEntity e) {
        return e instanceof PlayerEntity || DiseaseRegistry.getAllReservoirHosts().contains(e.getType());
    }

    private static boolean isTargetSusceptibleTo(LivingEntity t, Disease disease) {
        return t instanceof PlayerEntity || DiseaseRegistry.getReservoirHosts(disease.id()).contains(t.getType());
    }

    private static boolean isInfected(LivingEntity e) {
        InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
        return s != null && s.isInfected();
    }
    private static boolean isInfectious(LivingEntity e) {
        InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
        return s != null && s.isInfectious();
    }
    private static boolean isImmune(LivingEntity e) {
        InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
        return s != null && s.isImmune();
    }
}
