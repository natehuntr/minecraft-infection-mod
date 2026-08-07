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
import java.util.List;
import java.util.stream.Collectors;

@Environment(EnvType.CLIENT)
public final class InfectionHudOverlay {
    public static volatile boolean infected = false;
    public static volatile boolean exposed = false;
    public static volatile String diseaseId = "";
    public static volatile int incubationTicksRemaining = 0;
    public static volatile int ticksRemaining = 0;
    public static volatile int permanentHeartsLost = 0;
    public static volatile List<String> symptomIds = List.of();
    public static volatile int symptomTicksRemaining = 0;

    private static final Identifier HEART_CONTAINER = Identifier.ofVanilla("hud/heart/container");

    private InfectionHudOverlay() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;

        int baseX = context.getScaledWindowWidth() / 2 - 91;
        int heartY = context.getScaledWindowHeight() - 39;

        context.getMatrices().push();
        context.getMatrices().translate(0, 0, 200);

        // Show temporary-loss hearts only while infectious (health penalty is active)
        boolean infectious = infected && !exposed;
        if (infectious) {
            int firstTempSlot = 10 - permanentHeartsLost - 2;
            for (int i = 0; i < 2; i++) {
                context.drawGuiTexture(
                        RenderLayer::getGuiTextured,
                        HEART_CONTAINER,
                        baseX + (firstTempSlot + i) * 8, heartY, 9, 9,
                        0xBFFF00FF
                );
            }
        }

        if (permanentHeartsLost > 0) {
            int firstPermSlot = 10 - permanentHeartsLost;
            for (int i = 0; i < permanentHeartsLost; i++) {
                context.drawGuiTexture(
                        RenderLayer::getGuiTextured,
                        HEART_CONTAINER,
                        baseX + (firstPermSlot + i) * 8, heartY, 9, 9,
                        0xFF505050
                );
            }
        }

        context.getMatrices().pop();

        if (!infected) return;

        Disease disease = DiseaseRegistry.get(diseaseId);
        String name = disease != null ? disease.displayName() : diseaseId;
        int cx = context.getScaledWindowWidth() / 2;

        if (exposed) {
            // Incubation phase — not yet symptomatic
            int seconds = incubationTicksRemaining / 20;
            String timerStr = String.format("%d:%02d", seconds / 60, seconds % 60);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Exposed: " + name), cx, 20, 0xFFAA55);
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Infectious in: " + timerStr), cx, 30, 0xFFCC88);
        } else {
            // Contagious. Derived the same way the server does it, from the disease's
            // prodrome length, so no extra field is needed on the sync payload.
            boolean rash = disease == null
                    || disease.prodromeTicks() <= 0
                    || ticksRemaining <= disease.rashOnsetTicks();

            if (!rash) {
                // Prodrome — already spreading it, with nothing yet to see
                int toRash = (ticksRemaining - disease.rashOnsetTicks()) / 20;
                String rashTimer = String.format("%d:%02d", toRash / 60, toRash % 60);
                context.drawCenteredTextWithShadow(client.textRenderer,
                        Text.literal("Unwell: " + name), cx, 20, 0xFFCC44);
                context.drawCenteredTextWithShadow(client.textRenderer,
                        Text.literal("Contagious — rash in: " + rashTimer), cx, 30, 0xFFDD88);
            } else {
                int seconds = ticksRemaining / 20;
                String timerStr = String.format("%d:%02d", seconds / 60, seconds % 60);
                context.drawCenteredTextWithShadow(client.textRenderer,
                        Text.literal("Infection: " + name), cx, 20, 0xFF5555);
                context.drawCenteredTextWithShadow(client.textRenderer,
                        Text.literal("Clears in: " + timerStr), cx, 30, 0xFFAAAA);
            }

            if (!symptomIds.isEmpty() && symptomTicksRemaining > 0) {
                String names = symptomIds.stream()
                        .map(InfectionHudOverlay::symptomDisplayName)
                        .collect(Collectors.joining(", "));
                context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Symptoms: " + names), cx, 40, 0xFFAA00);
            }
        }
    }

    private static String symptomDisplayName(String id) {
        return switch (id) {
            case "slowness" -> "Fatigue";
            case "nausea"   -> "Nausea";
            case "weakness" -> "Weakness";
            default         -> id;
        };
    }
}
