package com.natehuntr.infectionmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.natehuntr.infectionmod.infection.InfectionAttachments;
import com.natehuntr.infectionmod.infection.InfectionManager;
import com.natehuntr.infectionmod.infection.InfectionState;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;

public final class InfectionCommand {

    private InfectionCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("infect")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> infectPlayer(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                .then(CommandManager.argument("target", EntityArgumentType.player())
                        .executes(ctx -> infectPlayer(ctx.getSource(), EntityArgumentType.getPlayer(ctx, "target")))
                )
        );

        dispatcher.register(CommandManager.literal("recover")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> recoverPlayer(ctx.getSource(), ctx.getSource().getPlayerOrThrow()))
                .then(CommandManager.argument("target", EntityArgumentType.player())
                        .executes(ctx -> recoverPlayer(ctx.getSource(), EntityArgumentType.getPlayer(ctx, "target")))
                )
        );

        dispatcher.register(CommandManager.literal("infection-status")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> showStatus(ctx.getSource()))
        );
    }

    private static int infectPlayer(ServerCommandSource source, ServerPlayerEntity player) {
        InfectionManager.infect(player, DiseaseRegistry.CRIMSON_FEVER);
        source.sendFeedback(() -> Text.literal("Infected " + player.getName().getString() + " with Crimson Fever"), false);
        return 1;
    }

    private static int recoverPlayer(ServerCommandSource source, ServerPlayerEntity player) {
        InfectionState state = player.getAttachedOrCreate(InfectionAttachments.INFECTION);
        if (!state.isInfected()) {
            source.sendFeedback(() -> Text.literal(player.getName().getString() + " is not infected"), false);
            return 0;
        }
        state.clearInfection();
        InfectionManager.reapplyOnLogin(player);
        source.sendFeedback(() -> Text.literal("Cleared infection from " + player.getName().getString()), false);
        return 1;
    }

    private static int showStatus(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        List<LivingEntity> nearby = player.getServerWorld().getEntitiesByClass(
                LivingEntity.class,
                player.getBoundingBox().expand(50),
                e -> {
                    InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
                    return s != null && (s.isInfected() || s.isImmune() || s.getPermanentHeartsLost() > 0);
                }
        );

        if (nearby.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No infected/immune entities within 50 blocks"), false);
            return 0;
        }

        for (LivingEntity e : nearby) {
            InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
            String name = e.getName().getString();
            String status;
            if (s.isInfected()) {
                int secs = s.getTicksRemaining() / 20;
                status = "INFECTED (" + s.getDiseaseId() + ") " + secs + "s remaining";
            } else if (s.isImmune()) {
                int secs = s.getImmunityTicksRemaining() / 20;
                status = "IMMUNE " + secs + "s remaining";
            } else {
                status = "perm hearts lost: " + s.getPermanentHeartsLost();
            }
            source.sendFeedback(() -> Text.literal("  " + name + ": " + status), false);
        }
        return 1;
    }
}