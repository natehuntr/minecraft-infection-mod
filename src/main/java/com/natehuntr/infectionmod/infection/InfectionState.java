package com.natehuntr.infectionmod.infection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

public class InfectionState {
    private String diseaseId;
    private int ticksRemaining;
    private boolean immune;
    private int immunityTicksRemaining;
    private int infectionCount;
    private int permanentHeartsLost;

    public InfectionState() {}

    public InfectionState(Optional<String> diseaseId, int ticksRemaining, boolean immune,
                          int immunityTicksRemaining, int infectionCount, int permanentHeartsLost) {
        this.diseaseId = diseaseId.orElse(null);
        this.ticksRemaining = ticksRemaining;
        this.immune = immune;
        this.immunityTicksRemaining = immunityTicksRemaining;
        this.infectionCount = infectionCount;
        this.permanentHeartsLost = permanentHeartsLost;
    }

    public static final Codec<InfectionState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.STRING.optionalFieldOf("disease_id").forGetter(s -> Optional.ofNullable(s.diseaseId)),
        Codec.INT.optionalFieldOf("ticks_remaining", 0).forGetter(s -> s.ticksRemaining),
        Codec.BOOL.optionalFieldOf("immune", false).forGetter(s -> s.immune),
        Codec.INT.optionalFieldOf("immunity_ticks_remaining", 0).forGetter(s -> s.immunityTicksRemaining),
        Codec.INT.optionalFieldOf("infection_count", 0).forGetter(s -> s.infectionCount),
        Codec.INT.optionalFieldOf("permanent_hearts_lost", 0).forGetter(s -> s.permanentHeartsLost)
    ).apply(inst, InfectionState::new));

    public boolean isInfected() { return diseaseId != null; }
    public String getDiseaseId() { return diseaseId; }
    public int getTicksRemaining() { return ticksRemaining; }
    public boolean isImmune() { return immune; }
    public int getImmunityTicksRemaining() { return immunityTicksRemaining; }
    public int getInfectionCount() { return infectionCount; }
    public int getPermanentHeartsLost() { return permanentHeartsLost; }

    public void infect(String id, int durationTicks) {
        this.diseaseId = id;
        this.ticksRemaining = durationTicks;
    }
    public void tickInfection() { if (ticksRemaining > 0) ticksRemaining--; }
    public void tickImmunity() { if (immunityTicksRemaining > 0) immunityTicksRemaining--; }

    public void recover(int immunityDurationTicks) {
        infectionCount++;
        diseaseId = null;
        ticksRemaining = 0;
        if (immunityDurationTicks > 0) { immune = true; immunityTicksRemaining = immunityDurationTicks; }
    }
    public void clearInfection() { diseaseId = null; ticksRemaining = 0; }
    public void clearImmunity() { immune = false; immunityTicksRemaining = 0; }
    public void recordPermanentHeartLoss() { permanentHeartsLost++; }
    public float permanentLossChance() { return Math.min(0.10f * (infectionCount + 1), 0.90f); }
}
