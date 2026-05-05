package com.natehuntr.infectionmod.infection;

import com.natehuntr.infectionmod.InfectionMod;
import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InfectionManager {
    private static final double PROXIMITY_RADIUS = 3.0;
    private static final float SPAWN_INFECTION_CHANCE = 0.05f;
    private static final Identifier TEMP_HEALTH_ID =
            Identifier.of(InfectionMod.MOD_ID, "infection_health_penalty");

    static final Set<EntityType<?>> RESERVOIR_HOSTS = Set.of(
            EntityType.BAT, EntityType.PIG, EntityType.COW, EntityType.CHICKEN,
            EntityType.SHEEP, EntityType.FOX, EntityType.WOLF, EntityType.CAT,
            EntityType.VILLAGER, EntityType.HORSE, EntityType.DONKEY, EntityType.MULE,
            EntityType.RABBIT
    );

    private InfectionManager() {}

    public static void tick(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers()) tickPlayer(world, player);
        if (world.getTime() % 20 == 0) spreadDisease(world);
    }

    private static void tickPlayer(ServerWorld world, ServerPlayerEntity player) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (state.isInfected()) {
            state.tickInfection();
            if (state.getTicksRemaining() <= 0) {
                recover(world, player, state);
            } else {
                ensureHealthPenalty(player);
                if (world.getTime() % 20 == 0) syncToClient(player, state);
            }
        } else if (state.isImmune()) {
            state.tickImmunity();
            if (state.getImmunityTicksRemaining() <= 0) { state.clearImmunity(); syncToClient(player, state); }
        }
    }

    private static void spreadDisease(ServerWorld world) {
        Set<LivingEntity> sources = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            world.getEntitiesByClass(LivingEntity.class,
                    player.getBoundingBox().expand(PROXIMITY_RADIUS + 8),
                    e -> isSusceptible(e) && isInfected(e)
            ).forEach(sources::add);
        }
        for (LivingEntity source : sources) {
            InfectionState srcState = source.getAttached(InfectionAttachments.INFECTION);
            if (srcState == null) continue;
            Disease disease = DiseaseRegistry.get(srcState.getDiseaseId());
            if (disease == null) continue;
            List<LivingEntity> targets = world.getEntitiesByClass(LivingEntity.class,
                    source.getBoundingBox().expand(PROXIMITY_RADIUS),
                    t -> t != source && isSusceptible(t) && !isInfected(t) && !isImmune(t)
            );
            for (LivingEntity target : targets) {
                boolean contact = source.getBoundingBox().intersects(target.getBoundingBox());
                float chance = contact ? Math.min(disease.baseTransmissionRate() * 2, 1.0f) : disease.baseTransmissionRate();
                if (world.getRandom().nextFloat() < chance) infect(target, disease);
            }
        }
    }

    public static void infect(LivingEntity entity, Disease disease) {
        InfectionState state = entity.getAttachedOrCreate(InfectionAttachments.INFECTION);
        state.infect(disease.id(), disease.durationTicks());
        if (entity instanceof ServerPlayerEntity player) { ensureHealthPenalty(player); syncToClient(player, state); }
    }

    private static void recover(ServerWorld world, ServerPlayerEntity player, InfectionState state) {
        removeHealthPenalty(player);
        double maxHealth = player.getAttributeValue(EntityAttributes.MAX_HEALTH);
        if (world.getRandom().nextFloat() < state.permanentLossChance() && maxHealth - 2.0 >= 8.0) {
            state.recordPermanentHeartLoss();
            applyPermanentLoss(player, state.getPermanentHeartsLost());
            if (player.getHealth() > player.getMaxHealth()) player.setHealth(player.getMaxHealth());
        }
        Disease disease = DiseaseRegistry.get(state.getDiseaseId());
        state.recover(disease != null ? disease.immunityDurationTicks() : 0);
        syncToClient(player, state);
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
        if (state.isInfected()) ensureHealthPenalty(player);
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
                state.isInfected(), state.getDiseaseId() != null ? state.getDiseaseId() : "", state.getTicksRemaining(), state.getPermanentHeartsLost()
        ));
    }

    public static boolean isSusceptible(LivingEntity e) { return e instanceof PlayerEntity || RESERVOIR_HOSTS.contains(e.getType()); }

    public static void onEntityLoad(Entity entity, ServerWorld world) {
        if (!(entity instanceof LivingEntity living)) return;
        if (!RESERVOIR_HOSTS.contains(living.getType())) return;
        if (living.getAttached(InfectionAttachments.INFECTION) != null) return;
        living.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (world.getRandom().nextFloat() < SPAWN_INFECTION_CHANCE) {
            infect(living, DiseaseRegistry.CRIMSON_FEVER);
        }
    }

    private static boolean isInfected(LivingEntity e) { InfectionState s = e.getAttached(InfectionAttachments.INFECTION); return s != null && s.isInfected(); }
    private static boolean isImmune(LivingEntity e) { InfectionState s = e.getAttached(InfectionAttachments.INFECTION); return s != null && s.isImmune(); }
}