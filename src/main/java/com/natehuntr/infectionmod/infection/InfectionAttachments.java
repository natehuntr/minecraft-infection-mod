package com.natehuntr.infectionmod.infection;

import com.natehuntr.infectionmod.InfectionMod;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.util.Identifier;

public final class InfectionAttachments {
    public static final AttachmentType<InfectionState> INFECTION =
        AttachmentRegistry.create(
            Identifier.of(InfectionMod.MOD_ID, "infection"),
            builder -> builder.initializer(InfectionState::new).persistent(InfectionState.CODEC)
        );
    private InfectionAttachments() {}
    public static void init() {}
}
