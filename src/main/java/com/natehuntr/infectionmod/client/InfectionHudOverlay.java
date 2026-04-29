package com.natehuntr.infectionmod.client;

import com.natehuntr.infectionmod.disease.Disease;
import com.natehuntr.infectionmod.disease.DiseaseRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public final class InfectionHudOverlay {
    public static volatile boolean infected = false;
    public static volatile String diseaseId = "";
    public static volatile int ticksRemaining = 0;

    private InfectionHudOverlay() {}

    public static void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!infected) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;
        Disease disease = DiseaseRegistry.get(diseaseId);
        String name = disease != null ? disease.displayName() : diseaseId;
        int seconds = ticksRemaining / 20;
        String timerStr = String.format("%d:%02d", seconds / 60, seconds % 60);
        int cx = context.getScaledWindowWidth() / 2;
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Infection: " + name), cx, 20, 0xFF5555);
        context.drawCenteredTextWithShadow(client.textRenderer, Text.literal("Clears in: " + timerStr), cx, 30, 0xFFAAAA);
    }
}
