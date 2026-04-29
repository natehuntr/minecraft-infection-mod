import os

base = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(base, "src/main/java/com/natehuntr/infectionmod")

files = {}

files["network/InfectionSyncPayload.java"] = """\
package com.natehuntr.infectionmod.network;

import com.natehuntr.infectionmod.InfectionMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record InfectionSyncPayload(boolean infected, String diseaseId, int ticksRemaining, int permanentHeartsLost)
        implements CustomPayload {
    public static final CustomPayload.Id<InfectionSyncPayload> ID =
        new CustomPayload.Id<>(Identifier.of(InfectionMod.MOD_ID, "infection_sync"));
    public static final PacketCodec<PacketByteBuf, InfectionSyncPayload> CODEC = PacketCodec.of(
        (payload, buf) -> { buf.writeBoolean(payload.infected()); buf.writeString(payload.diseaseId()); buf.writeVarInt(payload.ticksRemaining()); buf.writeVarInt(payload.permanentHeartsLost()); },
        buf -> new InfectionSyncPayload(buf.readBoolean(), buf.readString(), buf.readVarInt(), buf.readVarInt())
    );
    @Override public CustomPayload.Id<? extends CustomPayload> getId() { return ID; }
}
"""

files["client/InfectionHudOverlay.java"] = """\
package com.natehuntr.infectionmod.client;

import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public final class InfectionHudOverlay {
    public static volatile boolean infected = false;
    public static volatile String diseaseId = "";
    public static volatile int ticksRemaining = 0;
    public static volatile int permanentHeartsLost = 0;

    private static final Identifier HEART_CONTAINER = Identifier.ofVanilla("hud/heart/container");

    private InfectionHudOverlay() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;

        if (permanentHeartsLost > 0) {
            int baseX = context.getScaledWindowWidth() / 2 - 91;
            int heartY = context.getScaledWindowHeight() - 39;
            int firstGreySlot = 10 - permanentHeartsLost;
            for (int i = 0; i < permanentHeartsLost; i++) {
                context.drawGuiTexture(
                    RenderLayer::getGuiTextured,
                    HEART_CONTAINER,
                    baseX + (firstGreySlot + i) * 8, heartY, 9, 9,
                    0xFF606060
                );
            }
        }

        if (!infected) return;

        Disease disease = DiseaseRegistry.get(diseaseId);
        String name = disease != null ? disease.displayName() : diseaseId;
        int seconds = ticksRemaining / 20;
        String timerStr = String.format("%d:%02d", seconds / 60, seconds % 60);
        int cx = context.getScaledWindowWidth() / 2;
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Infection: " + name), cx, 20, 0xFF5555);
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Clears in: " + timerStr), cx, 30, 0xFFAAAA);
    }
}
"""

files["client/InfectionModClient.java"] = """\
package com.natehuntr.infectionmod.client;

import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

@Environment(EnvType.CLIENT)
public class InfectionModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(InfectionSyncPayload.ID,
            (payload, context) -> context.client().execute(() -> {
                InfectionHudOverlay.infected = payload.infected();
                InfectionHudOverlay.diseaseId = payload.diseaseId();
                InfectionHudOverlay.ticksRemaining = payload.ticksRemaining();
                InfectionHudOverlay.permanentHeartsLost = payload.permanentHeartsLost();
            })
        );
        HudRenderCallback.EVENT.register(InfectionHudOverlay::render);
    }
}
"""

files["infection/InfectionManager.java"] = """\
package com.natehuntr.infectionmod.infection;

import com.natehuntr.infectionmod.InfectionMod;
import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.network.InfectionSyncPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class InfectionManager {
    private static final double PROXIMITY_RADIUS = 3.0;
    private static final Identifier TEMP_HEALTH_ID =
        Identifier.of(InfectionMod.MOD_ID, "infection_health_penalty");

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

    public static boolean isSusceptible(LivingEntity e) { return e instanceof PlayerEntity || e instanceof PassiveEntity; }
    private static boolean isInfected(LivingEntity e) { InfectionState s = e.getAttached(InfectionAttachments.INFECTION); return s != null && s.isInfected(); }
    private static boolean isImmune(LivingEntity e) { InfectionState s = e.getAttached(InfectionAttachments.INFECTION); return s != null && s.isImmune(); }
}
"""

for rel, content in files.items():
    path = os.path.join(src, rel)
    with open(path, "w") as f:
        f.write(content)
    print(f"Written: {rel}")

print("Done.")