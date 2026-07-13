package com.natehuntr.infectionmod.infection;

import com.natehuntr.infectionmod.InfectionMod;
import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InfectionManager {
    private static final double PROXIMITY_RADIUS = 3.0;
    private static final double PROXIMITY_RADIUS_MEDIUM = 6.0;
    private static final float SPAWN_INFECTION_CHANCE = 0.05f;
    private static final int SYMPTOM_TICKS_MIN = 3600;
    private static final int SYMPTOM_TICKS_MAX = 24000;
    private static final List<String> SYMPTOM_IDS = List.of("slowness", "nausea", "weakness");
    private static final Identifier TEMP_HEALTH_ID =
            Identifier.of(InfectionMod.MOD_ID, "infection_health_penalty");

    private InfectionManager() {}

    public static void tick(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) tickPlayer(world, player);
        if (world.getTime() % 20 == 0) spreadDisease(world);
    }

    private static void tickPlayer(ServerWorld world, ServerPlayerEntity player) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);

        if (state.isExposed()) {
            state.tickIncubation();
            if (state.getIncubationTicksRemaining() <= 0) {
                // Incubation just ended — become infectious
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
                recover(world, player, state);
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

    private static void spreadDisease(ServerWorld world) {
        Set<LivingEntity> sources = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            world.getEntitiesByClass(LivingEntity.class,
                    player.getBoundingBox().expand(PROXIMITY_RADIUS_MEDIUM + 8),
                    e -> isInfectious(e)
            ).forEach(sources::add);
        }
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
                boolean closeRange = source.getBoundingBox().expand(PROXIMITY_RADIUS).intersects(target.getBoundingBox());
                float chance;
                if (contact) {
                    chance = Math.min(disease.baseTransmissionRate() * 2, 1.0f);
                } else if (closeRange) {
                    chance = disease.baseTransmissionRate();
                } else {
                    // Medium range: 1/6 of base rate, min 0.1%
                    chance = Math.max(disease.baseTransmissionRate() / 6.0f, 0.001f);
                }
                if (world.getRandom().nextFloat() < chance) infect(target, disease);
            }
        }
    }

    public static void infect(LivingEntity entity, Disease disease) {
        InfectionState state = entity.getAttachedOrCreate(InfectionAttachments.INFECTION);
        state.infect(disease.id(), disease.incubationTicks(), disease.durationTicks());
        if (entity instanceof ServerPlayerEntity player) {
            if (disease.incubationTicks() <= 0) {
                ensureHealthPenalty(player);
                rollSymptoms(player.getServerWorld(), state);
            }
            syncToClient(player, state);
        }
    }

    private static void recover(ServerWorld world, ServerPlayerEntity player, InfectionState state) {
        Disease disease = DiseaseRegistry.get(state.getDiseaseId());
        float cfr = disease != null ? disease.caseFatalityRate() : 0f;

        removeHealthPenalty(player);
        for (String id : state.getActiveSymptomIds()) {
            RegistryEntry<StatusEffect> effect = effectForId(id);
            if (effect != null) player.removeStatusEffect(effect);
        }

        if (cfr > 0 && world.getRandom().nextFloat() < cfr) {
            // Fatal outcome — clear state before kill so respawn starts clean
            state.clearInfection();
            syncToClient(player, state);
            player.damage(world, world.getDamageSources().generic(), Float.MAX_VALUE);
            return;
        }

        // Survival — check for permanent heart loss
        double maxHealth = player.getAttributeValue(EntityAttributes.MAX_HEALTH);
        if (world.getRandom().nextFloat() < state.permanentLossChance() && maxHealth - 2.0 >= 8.0) {
            state.recordPermanentHeartLoss();
            applyPermanentLoss(player, state.getPermanentHeartsLost());
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        state.recover(disease != null ? disease.immunityDurationTicks() : 0);
        syncToClient(player, state);
    }

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

    // Any entity that can contract at least one disease
    public static boolean isSusceptible(LivingEntity e) {
        return e instanceof PlayerEntity || DiseaseRegistry.getAllReservoirHosts().contains(e.getType());
    }

    // Whether a specific entity can host a specific disease
    private static boolean isTargetSusceptibleTo(LivingEntity t, Disease disease) {
        return t instanceof PlayerEntity || DiseaseRegistry.getReservoirHosts(disease.id()).contains(t.getType());
    }

    public static void onEntityLoad(Entity entity, ServerWorld world) {
        if (!(entity instanceof LivingEntity living)) return;
        List<Disease> hostDiseases = DiseaseRegistry.getDiseasesForHost(living.getType());
        if (hostDiseases.isEmpty()) return;
        if (living.getAttached(InfectionAttachments.INFECTION) != null) return;
        living.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (world.getRandom().nextFloat() < SPAWN_INFECTION_CHANCE) {
            Disease disease = hostDiseases.get(world.getRandom().nextInt(hostDiseases.size()));
            infect(living, disease);
        }
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
