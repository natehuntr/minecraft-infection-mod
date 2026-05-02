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

        int baseX = context.getScaledWindowWidth() / 2 - 91;
        int heartY = context.getScaledWindowHeight() - 39;

        // Reddish-grey hearts for the 2 hearts temporarily lost to infection
        if (infected) {
            int firstTempSlot = 10 - permanentHeartsLost - 2;
            for (int i = 0; i < 2; i++) {
                context.drawGuiTexture(
                    RenderLayer::getGuiTextured,
                    HEART_CONTAINER,
                    baseX + (firstTempSlot + i) * 8, heartY, 9, 9,
                    0xFF35063
                );
            }
        }

        // Dark grey hearts for permanent losses (rightmost slots)
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
