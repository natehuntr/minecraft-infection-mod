package com.natehuntr.infectionmod.infection;

import com.natehuntr.infectionmod.InfectionMod;
import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.item.InfectionItems;
import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import com.natehuntr.infectionmod.network.VillagerInfectionPayload;
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
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageType;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    /**
     * Damage source naming the disease, so a fatality reads "Steve succumbed to Scarlet
     * Blight" rather than a bare "Steve died". Each disease has a damage_type JSON under
     * data/infection_mod/damage_type/ whose message_id resolves the lang key.
     */
    private static DamageSource diseaseDamage(ServerWorld world, String diseaseId) {
        Registry<DamageType> registry = world.getRegistryManager().getOrThrow(RegistryKeys.DAMAGE_TYPE);
        RegistryKey<DamageType> key =
                RegistryKey.of(RegistryKeys.DAMAGE_TYPE, Identifier.of(InfectionMod.MOD_ID, diseaseId));
        return new DamageSource(registry.getEntry(registry.getValueOrThrow(key)));
    }

    // Visible sign of an active infection, one signature per disease. These are all
    // SimpleParticleTypes so they need no constructor args and are stable across versions.
    private static ParticleEffect rashParticle(String diseaseId) {
        return switch (diseaseId) {
            case "scarlet_blight" -> ParticleTypes.CRIMSON_SPORE;   // red speckle — the rash
            case "crimson_fever"  -> ParticleTypes.SNEEZE;          // respiratory droplets
            case "wasting_curse"  -> ParticleTypes.ASH;             // grey, wasting away
            default               -> ParticleTypes.SNEEZE;
        };
    }

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
            syncVillagerAppearance(world);
        }
    }

    /**
     * Tells each player which nearby villagers are visibly diseased, so the client can swap
     * their texture. Radius comfortably exceeds entity tracking range — a villager the
     * client cannot see costs one varint and saves a pop-in when it comes into view.
     */
    private static void syncVillagerAppearance(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            List<Integer> ids = new ArrayList<>();
            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class,
                    player.getBoundingBox().expand(128.0), InfectionManager::showsRash)) {
                ids.add(e.getId());
            }
            ServerPlayNetworking.send(player, new VillagerInfectionPayload(ids));
        }
    }

    /**
     * Only Scarlet Blight, and only once infectious — an incubating villager is not yet
     * contagious and must not be identifiable on sight.
     */
    private static boolean showsRash(LivingEntity e) {
        if (e.getType() != EntityType.VILLAGER) return false;
        InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
        return s != null && s.isInfectious() && "scarlet_blight".equals(s.getDiseaseId());
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
                if (world.getTime() % 10 == 0) spawnDiseaseParticles(world, player, state.getDiseaseId());
                if (world.getTime() % 20 == 0) {
                    applySymptomEffects(player, state);
                    syncToClient(player, state);
                }
            }
        } else if (state.hasAnyImmunity()) {
            if (state.tickImmunity()) syncToClient(player, state);
        }
    }

    // -------------------------------------------------------------------------
    // Animal ticking — called once per second (every 20 game ticks)
    // -------------------------------------------------------------------------

    /**
     * Advances every infected non-player entity in this world by one second.
     *
     * Driven off the world's own entity list rather than a tracking set: END_WORLD_TICK
     * fires once per dimension, so a set shared across worlds would see its members
     * "missing" on every foreign dimension's pass. This also means state survives chunk
     * reloads and server restarts for free — an entity resumes ticking as soon as its
     * chunk is loaded, because being in the world IS the registration.
     */
    private static void tickAnimals(ServerWorld world) {
        // Collected first: recoverAnimal can kill an entity, and mutating the entity
        // list while iterating it is not safe.
        List<LivingEntity> active = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living instanceof ServerPlayerEntity || living.isRemoved()) continue;
            InfectionState state = living.getAttached(InfectionAttachments.INFECTION);
            // Immune-but-healthy entities are included so their immunity actually expires;
            // collecting only infected ones left recovered animals immune forever.
            if (state == null || (!state.isInfected() && !state.hasAnyImmunity())) continue;
            active.add(living);
        }

        for (LivingEntity living : active) {
            InfectionState state = living.getAttached(InfectionAttachments.INFECTION);
            if (state == null) continue;   // may have changed above
            tickAnimal(world, living, state);
        }
    }

    private static void tickAnimal(ServerWorld world, LivingEntity animal, InfectionState state) {
        if (state.isExposed()) {
            // Advance incubation by one second (20 ticks) per call
            state.tickIncubation(20);
        } else if (state.isInfectious()) {
            spawnDiseaseParticles(world, animal, state.getDiseaseId());
            state.tickInfection(20);
            if (state.getTicksRemaining() <= 0) {
                recoverAnimal(world, animal, state);
            }
        } else {
            state.tickImmunity(20);
        }
    }

    /**
     * Renders the visible sign of infection. Scarlet Blight only — its rash is the one
     * disease whose real-world signature is something you can see across a room.
     */
    private static void spawnDiseaseParticles(ServerWorld world, LivingEntity entity, String diseaseId) {
        if (!"scarlet_blight".equals(diseaseId)) return;
        Box box = entity.getBoundingBox();
        double cx = (box.minX + box.maxX) / 2.0;
        double cy = (box.minY + box.maxY) / 2.0;
        double cz = (box.minZ + box.maxZ) / 2.0;
        double sx = (box.maxX - box.minX) / 2.0;
        double sy = (box.maxY - box.minY) / 2.0;

        world.spawnParticles(rashParticle(diseaseId), cx, cy, cz, 4, sx, sy, sx, 0.0);

        // Occasional respiratory puff at head height for the airborne diseases
        if (!"wasting_curse".equals(diseaseId) && world.getRandom().nextFloat() < 0.2f) {
            world.spawnParticles(ParticleTypes.SNEEZE, cx, box.maxY - 0.2, cz, 2, 0.1, 0.05, 0.1, 0.02);
        }
    }

    private static void recoverAnimal(ServerWorld world, LivingEntity animal, InfectionState state) {
        Disease disease = DiseaseRegistry.get(state.getDiseaseId());
        float cfr = disease != null ? disease.caseFatalityRate() : 0f;

        String diseaseId = state.getDiseaseId();
        if (cfr > 0 && world.getRandom().nextFloat() < cfr) {
            EpidemicLog.recordResolution(world.getTime(), diseaseId, animal, EpidemicLog.Outcome.DIED);
            // Mark as disease-kill so onAnimalDeath doesn't double-drop infected meat.
            // damage() fires death events synchronously, so remove immediately after.
            pendingDiseaseDeath.add(animal.getUuid());
            dropInfectedMeat(world, animal, diseaseId);
            animal.damage(world, diseaseDamage(world, diseaseId), Float.MAX_VALUE);
            pendingDiseaseDeath.remove(animal.getUuid());
        } else {
            EpidemicLog.recordResolution(world.getTime(), diseaseId, animal, EpidemicLog.Outcome.RECOVERED);
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
        // Every infectious entity in loaded chunks is a source, not just those standing
        // near a player. Gathering sources by player proximity confined transmission to a
        // ~14 block bubble, so an outbreak froze the moment you walked away: infected
        // villagers would tick all the way to recovery without infecting anyone, and no
        // epidemic could ever burn through a village or reach herd immunity off-screen.
        // Loaded chunks already bound the cost, and tickAnimals walks the same set.
        List<LivingEntity> sources = new ArrayList<>();
        for (Entity entity : world.iterateEntities()) {
            if (entity instanceof LivingEntity living && isInfectious(living)) sources.add(living);
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
                            && !isImmuneTo(t, disease.id())
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
                    infect(target, disease, source);
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

    /** Index case — spawn-seeded or /infect, with no known source. */
    public static void infect(LivingEntity entity, Disease disease) {
        infect(entity, disease, null);
    }

    public static void infect(LivingEntity entity, Disease disease, LivingEntity source) {
        InfectionState state = entity.getAttachedOrCreate(InfectionAttachments.INFECTION);
        state.infect(disease.id(), disease.incubationTicks(), disease.durationTicks());

        if (entity.getWorld() instanceof ServerWorld sw) {
            EpidemicLog.recordInfection(sw.getTime(), disease.id(), entity, source);
        }

        if (entity instanceof ServerPlayerEntity player) {
            if (disease.incubationTicks() <= 0) {
                ensureHealthPenalty(player);
                rollSymptoms(player.getServerWorld(), state);
            }
            syncToClient(player, state);
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

        String diseaseId = state.getDiseaseId();
        if (cfr > 0 && world.getRandom().nextFloat() < cfr) {
            EpidemicLog.recordResolution(world.getTime(), diseaseId, player, EpidemicLog.Outcome.DIED);
            state.clearInfection();
            syncToClient(player, state);
            player.damage(world, diseaseDamage(world, diseaseId), Float.MAX_VALUE);
            return;
        }
        EpidemicLog.recordResolution(world.getTime(), diseaseId, player, EpidemicLog.Outcome.RECOVERED);

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

    /**
     * Reconciles a player's attributes with their infection state, in BOTH directions.
     *
     * The else-branch matters: without it any caller that clears infection without also
     * dropping the modifier (notably /recover) leaves a permanent -4 MAX_HEALTH applied,
     * so the player silently keeps 8 hearts forever and re-infecting appears to hand back
     * two hearts that were never lost.
     */
    public static void reapplyOnLogin(ServerPlayerEntity player) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (state.isInfectious()) {
            ensureHealthPenalty(player);
            applySymptomEffects(player, state);
        } else {
            removeHealthPenalty(player);
        }
        applyPermanentLoss(player, state.getPermanentHeartsLost());
        syncToClient(player, state);
    }

    /**
     * Immediately ends an infection, undoing everything it applied. Used by /recover.
     *
     * Clearing InfectionState alone is not enough: the max-health modifier and any active
     * symptom effects live on the player, not in the state, and would outlive the disease.
     */
    public static void cure(LivingEntity entity) {
        InfectionState state = entity.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (entity instanceof ServerPlayerEntity player) {
            removeHealthPenalty(player);
            for (String id : state.getActiveSymptomIds()) {
                RegistryEntry<StatusEffect> effect = effectForId(id);
                if (effect != null) player.removeStatusEffect(effect);
            }
            state.clearInfection();
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
            syncToClient(player, state);
        } else {
            // Animals carry no attribute modifiers or status effects from the disease
            state.clearInfection();
        }
    }

    public static void handleRespawn(ServerPlayerEntity player, boolean fromDeath) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (fromDeath) state.clearInfection();
        // Reconcile rather than only re-applying permanent loss: this event also fires for
        // a non-death respawn (returning from the End), which hands over a fresh player
        // entity whose temporary modifier is gone while the infection is still running.
        reapplyOnLogin(player);
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

        // Only fresh spawns get a seeding roll. Anything with existing state has already
        // had one, and resumes ticking on its own now that tickAnimals walks the world.
        if (living.getAttached(InfectionAttachments.INFECTION) != null) return;

        living.getAttachedOrCreate(InfectionAttachments.INFECTION);

        // Newborns start susceptible. Seeding them would make breeding a source of new
        // INFECTIONS, when births are the one mechanism that restores susceptibles to a
        // village that has already burnt through an outbreak and gone immune.
        if (living.isBaby()) return;

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
    // Live population survey
    // -------------------------------------------------------------------------

    /** Current S/E/I/R counts across loaded chunks for one disease's eligible population. */
    public record LiveCounts(int susceptible, int exposed, int infectious, int immune) {
        public int total() { return susceptible + exposed + infectious + immune; }
    }

    public static LiveCounts countLive(ServerWorld world, String diseaseId) {
        Set<EntityType<?>> hosts = DiseaseRegistry.getReservoirHosts(diseaseId);
        int s = 0, e = 0, i = 0, r = 0;
        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (!(living instanceof PlayerEntity) && !hosts.contains(living.getType())) continue;

            InfectionState st = living.getAttached(InfectionAttachments.INFECTION);
            if (st == null) { s++; continue; }

            if (st.isInfected() && diseaseId.equals(st.getDiseaseId())) {
                if (st.isExposed()) e++; else i++;
            } else if (st.isImmuneTo(diseaseId)) {
                r++;
            } else if (!st.isInfected()) {
                s++;
            }
            // Entities infected with a DIFFERENT disease fall through deliberately:
            // co-infection is blocked, so they are neither susceptible nor immune here.
        }
        return new LiveCounts(s, e, i, r);
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
    private static boolean isImmuneTo(LivingEntity e, String diseaseId) {
        InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
        return s != null && s.isImmuneTo(diseaseId);
    }
}
