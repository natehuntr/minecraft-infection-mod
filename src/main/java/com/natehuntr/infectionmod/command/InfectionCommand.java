package com.natehuntr.infectionmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import com.natehuntr.infectionmod.infection.InfectionManager;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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
    }

    private static int infectPlayer(ServerCommandSource source, ServerPlayerEntity player) {
        InfectionManager.infect(player, DiseaseRegistry.CRIMSON_FEVER);
        source.sendFeedback(() -> Text.literal("Infected " + player.getName().getString() + " with Crimson Fever"), false);
        return 1;
    }
}