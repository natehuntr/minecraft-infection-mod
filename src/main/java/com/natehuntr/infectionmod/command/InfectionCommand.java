package com.natehuntr.infectionmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.infection.EpidemicLog;
import com.natehuntr.infectionmod.infection.InfectionAttachments;
import com.natehuntr.infectionmod.infection.InfectionManager;
import com.natehuntr.infectionmod.infection.InfectionState;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Collection;
import java.util.List;

public final class InfectionCommand {

    private InfectionCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Targets are entity selectors, not players: seeding an outbreak needs
        // /infect @e[type=villager,limit=3] scarlet_blight, and requiring a player meant
        // the only way to test animal disease was editing spawnInfectionChance and rebuilding.
        dispatcher.register(CommandManager.literal("infect")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> infectEntities(ctx.getSource(),
                        List.of(ctx.getSource().getPlayerOrThrow()),
                        DiseaseRegistry.CRIMSON_FEVER.id()))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(ctx -> infectEntities(ctx.getSource(),
                                EntityArgumentType.getEntities(ctx, "targets"),
                                DiseaseRegistry.CRIMSON_FEVER.id()))
                        .then(CommandManager.argument("disease", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    DiseaseRegistry.getAll().forEach(d -> builder.suggest(d.id()));
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> infectEntities(ctx.getSource(),
                                        EntityArgumentType.getEntities(ctx, "targets"),
                                        StringArgumentType.getString(ctx, "disease")))
                        )
                )
        );

        dispatcher.register(CommandManager.literal("recover")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> recoverEntities(ctx.getSource(),
                        List.of(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.argument("targets", EntityArgumentType.entities())
                        .executes(ctx -> recoverEntities(ctx.getSource(),
                                EntityArgumentType.getEntities(ctx, "targets")))
                )
        );

        dispatcher.register(CommandManager.literal("infection-status")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> showStatus(ctx.getSource()))
        );

        dispatcher.register(CommandManager.literal("infection-stats")
                .requires(src -> src.hasPermissionLevel(2))
                .executes(ctx -> showStats(ctx.getSource(), null))
                .then(CommandManager.argument("disease", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            DiseaseRegistry.getAll().forEach(d -> builder.suggest(d.id()));
                            return builder.buildFuture();
                        })
                        .executes(ctx -> showStats(ctx.getSource(), StringArgumentType.getString(ctx, "disease")))
                )
                .then(CommandManager.literal("reset")
                        .executes(ctx -> {
                            EpidemicLog.clear();
                            ctx.getSource().sendFeedback(() -> Text.literal("Epidemic log cleared"), false);
                            return 1;
                        })
                )
        );
    }

    private static int showStats(ServerCommandSource source, String filterId) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        List<String> ids = filterId != null
                ? List.of(filterId)
                : DiseaseRegistry.getAll().stream().map(Disease::id).toList();

        boolean any = false;
        for (String id : ids) {
            EpidemicLog.Stats st = EpidemicLog.stats(id);
            if (st == null) continue;
            any = true;

            Disease disease = DiseaseRegistry.get(id);
            String name = disease != null ? disease.displayName() : id;
            InfectionManager.LiveCounts live = InfectionManager.countLive(player.getServerWorld(), id);

            source.sendFeedback(() -> Text.literal("§6── " + name + " ──"), false);
            source.sendFeedback(() -> Text.literal(String.format(
                    "  Cases: %d total  (%d index, %d secondary)",
                    st.total(), st.indexCases(), st.secondaryCases())), false);

            String rStr = Double.isNaN(st.observedR())
                    ? "n/a (no case has finished spreading yet)"
                    : String.format("%.2f  over %d completed case(s), max %d",
                                    st.observedR(), st.completed(), st.maxSecondary());
            source.sendFeedback(() -> Text.literal("  Observed R: " + rStr), false);

            source.sendFeedback(() -> Text.literal(String.format(
                    "  Outcomes: %d recovered, %d died", st.recovered(), st.died())), false);
            source.sendFeedback(() -> Text.literal(String.format(
                    "  Loaded now: S %d | E %d | I %d | R %d  (of %d)",
                    live.susceptible(), live.exposed(), live.infectious(),
                    live.immune(), live.total())), false);

            int[] curve = st.curve();
            int peak = 1;
            for (int c : curve) peak = Math.max(peak, c);
            source.sendFeedback(() -> Text.literal("  Cases per MC day:"), false);
            for (int d = 0; d < curve.length; d++) {
                int bars = curve[d] * 24 / peak;
                String row = String.format("    d%-2d %s %d", d, "█".repeat(bars), curve[d]);
                source.sendFeedback(() -> Text.literal("§7" + row), false);
            }
            if (st.curveTruncated()) {
                source.sendFeedback(() -> Text.literal("§8    (later days folded into the last bar)"), false);
            }
        }

        if (!any) {
            source.sendFeedback(() -> Text.literal(
                    "No infections recorded yet. The log fills as the epidemic runs, "
                    + "and clears on server restart."), false);
            return 0;
        }
        return 1;
    }

    private static int infectEntities(ServerCommandSource source,
                                      Collection<? extends Entity> targets, String diseaseId) {
        Disease disease = DiseaseRegistry.get(diseaseId);
        if (disease == null) {
            source.sendFeedback(() -> Text.literal("Unknown disease: " + diseaseId), false);
            return 0;
        }

        int infected = 0, skipped = 0;
        String lastName = null;
        for (Entity entity : targets) {
            if (!(entity instanceof LivingEntity living)) { skipped++; continue; }
            // A host check would block deliberate testing, but a silent no-op would be
            // worse than a warning, so non-hosts are counted and reported.
            InfectionManager.infect(living, disease);
            infected++;
            lastName = living.getName().getString();
        }

        final int n = infected, bad = skipped;
        final String only = (infected == 1) ? lastName : null;
        source.sendFeedback(() -> Text.literal(
                only != null
                        ? "Infected " + only + " with " + disease.displayName()
                        : "Infected " + n + " entities with " + disease.displayName()
                          + (bad > 0 ? " (" + bad + " skipped: not living entities)" : "")), false);
        return infected;
    }

    private static int recoverEntities(ServerCommandSource source, Collection<? extends Entity> targets) {
        int cured = 0;
        String lastName = null;
        for (Entity entity : targets) {
            if (!(entity instanceof LivingEntity living)) continue;
            InfectionState state = living.getAttached(InfectionAttachments.INFECTION);
            if (state == null || !state.isInfected()) continue;
            InfectionManager.cure(living);
            cured++;
            lastName = living.getName().getString();
        }

        if (cured == 0) {
            source.sendFeedback(() -> Text.literal("No infected entities in selection"), false);
            return 0;
        }
        final int n = cured;
        final String only = (cured == 1) ? lastName : null;
        source.sendFeedback(() -> Text.literal(
                only != null ? "Cleared infection from " + only
                             : "Cleared infection from " + n + " entities"), false);
        return cured;
    }

    private static int showStatus(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        List<LivingEntity> nearby = player.getServerWorld().getEntitiesByClass(
                LivingEntity.class,
                player.getBoundingBox().expand(50),
                e -> {
                    InfectionState s = e.getAttached(InfectionAttachments.INFECTION);
                    return s != null && (s.isInfected() || s.hasAnyImmunity() || s.getPermanentHeartsLost() > 0);
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
            if (s.isExposed()) {
                int secs = s.getIncubationTicksRemaining() / 20;
                status = "EXPOSED (" + s.getDiseaseId() + ") infectious in " + secs + "s";
            } else if (s.isInfectious()) {
                int secs = s.getTicksRemaining() / 20;
                status = "INFECTIOUS (" + s.getDiseaseId() + ") " + secs + "s remaining";
            } else if (s.hasAnyImmunity()) {
                // Immunity is per-disease now, so list each one rather than a single timer
                StringBuilder sb = new StringBuilder("IMMUNE ");
                boolean first = true;
                for (var im : s.getImmunities().entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(im.getKey()).append(' ').append(im.getValue() / 20).append('s');
                    first = false;
                }
                status = sb.toString();
            } else {
                status = "perm hearts lost: " + s.getPermanentHeartsLost();
            }
            source.sendFeedback(() -> Text.literal("  " + name + ": " + status), false);
        }
        return 1;
    }
}
