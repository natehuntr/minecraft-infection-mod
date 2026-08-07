package com.natehuntr.infectionmod.client;

import com.natehuntr.infectionmod.InfectionMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.state.VillagerEntityRenderState;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.Identifier;

/**
 * Swaps the villager base texture while they are showing the Scarlet Blight rash.
 *
 * Since 1.21.2 the renderer no longer sees the entity in {@code getTexture} — it only gets
 * a render state. Rather than mixin into the vanilla state class to add a field, this
 * subclasses the renderer and overrides {@code createRenderState()} covariantly to supply
 * an extended state. {@code updateRenderState} still receives the entity, so that is where
 * the flag is read across from the client tracker.
 *
 * The profession/biome clothing overlay is a separate feature renderer and is untouched, so
 * an infected farmer still reads as a farmer — only the skin beneath changes.
 */
@Environment(EnvType.CLIENT)
public class InfectedVillagerRenderer extends VillagerEntityRenderer {

    public static final Identifier INFECTED_TEXTURE =
            Identifier.of(InfectionMod.MOD_ID, "textures/entity/villager/infected_villager.png");

    /** Vanilla villager state plus the one bit the texture choice depends on. */
    public static class InfectedVillagerRenderState extends VillagerEntityRenderState {
        public boolean showsRash;
    }

    public InfectedVillagerRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public VillagerEntityRenderState createRenderState() {
        return new InfectedVillagerRenderState();
    }

    @Override
    public void updateRenderState(VillagerEntity entity, VillagerEntityRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        if (state instanceof InfectedVillagerRenderState infectedState) {
            infectedState.showsRash = InfectedVillagerTracker.isInfected(entity.getId());
        }
    }

    @Override
    public Identifier getTexture(VillagerEntityRenderState state) {
        if (state instanceof InfectedVillagerRenderState infectedState && infectedState.showsRash) {
            return INFECTED_TEXTURE;
        }
        return super.getTexture(state);
    }
}
