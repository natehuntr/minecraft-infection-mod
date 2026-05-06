package com.natehuntr.infectionmod.infection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InfectionState {
    private String diseaseId;
    private int ticksRemaining;
    private boolean immune;
    private int immunityTicksRemaining;
    private int infectionCount;
    private int permanentHeartsLost;
    private List<String> activeSymptomIds = new ArrayList<>();
    private int symptomTicksRemaining = 0;

    public InfectionState() {}

    public InfectionState(Optional<String> diseaseId, int ticksRemaining, boolean immune,
                          int immunityTicksRemaining, int infectionCount, int permanentHeartsLost,
                          List<String> activeSymptomIds, int symptomTicksRemaining) {
        this.diseaseId = diseaseId.orElse(null);
        this.ticksRemaining = ticksRemaining;
        this.immune = immune;
        this.immunityTicksRemaining = immunityTicksRemaining;
        this.infectionCount = infectionCount;
        this.permanentHeartsLost = permanentHeartsLost;
        this.activeSymptomIds = new ArrayList<>(activeSymptomIds);
        this.symptomTicksRemaining = symptomTicksRemaining;
    }

    public static final Codec<InfectionState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("disease_id").forGetter(s -> Optional.ofNullable(s.diseaseId)),
            Codec.INT.optionalFieldOf("ticks_remaining", 0).forGetter(s -> s.ticksRemaining),
            Codec.BOOL.optionalFieldOf("immune", false).forGetter(s -> s.immune),
            Codec.INT.optionalFieldOf("immunity_ticks_remaining", 0).forGetter(s -> s.immunityTicksRemaining),
            Codec.INT.optionalFieldOf("infection_count", 0).forGetter(s -> s.infectionCount),
            Codec.INT.optionalFieldOf("permanent_hearts_lost", 0).forGetter(s -> s.permanentHeartsLost),
            Codec.list(Codec.STRING).optionalFieldOf("active_symptom_ids", List.of()).forGetter(s -> s.activeSymptomIds),
            Codec.INT.optionalFieldOf("symptom_ticks_remaining", 0).forGetter(s -> s.symptomTicksRemaining)
    ).apply(inst, InfectionState::new));

    public boolean isInfected() { return diseaseId != null; }
    public String getDiseaseId() { return diseaseId; }
    public int getTicksRemaining() { return ticksRemaining; }
    public boolean isImmune() { return immune; }
    public int getImmunityTicksRemaining() { return immunityTicksRemaining; }
    public int getInfectionCount() { return infectionCount; }
    public int getPermanentHeartsLost() { return permanentHeartsLost; }
    public boolean hasSymptoms() { return !activeSymptomIds.isEmpty() && symptomTicksRemaining > 0; }
    public List<String> getActiveSymptomIds() { return activeSymptomIds; }
    public int getSymptomTicksRemaining() { return symptomTicksRemaining; }

    public void infect(String id, int durationTicks) {
        this.diseaseId = id;
        this.ticksRemaining = durationTicks;
    }
    public void tickInfection() { if (ticksRemaining > 0) ticksRemaining--; }
    public void tickImmunity() { if (immunityTicksRemaining > 0) immunityTicksRemaining--; }
    public void tickSymptoms() { if (symptomTicksRemaining > 0) symptomTicksRemaining--; }

    public void setSymptoms(List<String> ids, int durationTicks) {
        this.activeSymptomIds = new ArrayList<>(ids);
        this.symptomTicksRemaining = durationTicks;
    }
    public void clearSymptoms() { activeSymptomIds.clear(); symptomTicksRemaining = 0; }

    public void recover(int immunityDurationTicks) {
        infectionCount++;
        diseaseId = null;
        ticksRemaining = 0;
        clearSymptoms();
        if (immunityDurationTicks > 0) { immune = true; immunityTicksRemaining = immunityDurationTicks; }
    }
    public void clearInfection() { diseaseId = null; ticksRemaining = 0; clearSymptoms(); }
    public void clearImmunity() { immune = false; immunityTicksRemaining = 0; }
    public void recordPermanentHeartLoss() { permanentHeartsLost++; }
    public float permanentLossChance() { return Math.min(0.10f * (infectionCount + 1), 0.90f); }
}