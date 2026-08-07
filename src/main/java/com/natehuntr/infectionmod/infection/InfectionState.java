package com.natehuntr.infectionmod.infection;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InfectionState {
    private String diseaseId;
    private int incubationTicksRemaining = 0;
    private int ticksRemaining;

    /**
     * Remaining immunity in ticks, per disease id.
     *
     * Replaces the previous single {@code immune} flag, which had no disease attached:
     * recovering from any one disease made an entity immune to all three, which both
     * blocked legitimate transmission and inflated the R compartment in /infection-stats.
     * An absent key means no immunity to that disease.
     */
    private Map<String, Integer> immunities = new HashMap<>();

    private int infectionCount;
    private int permanentHeartsLost;
    private List<String> activeSymptomIds = new ArrayList<>();
    private int symptomTicksRemaining = 0;

    public InfectionState() {}

    public InfectionState(Optional<String> diseaseId, int incubationTicksRemaining, int ticksRemaining,
                          Map<String, Integer> immunities, int infectionCount,
                          int permanentHeartsLost, List<String> activeSymptomIds, int symptomTicksRemaining) {
        this.diseaseId = diseaseId.orElse(null);
        this.incubationTicksRemaining = incubationTicksRemaining;
        this.ticksRemaining = ticksRemaining;
        this.immunities = new HashMap<>(immunities);
        this.infectionCount = infectionCount;
        this.permanentHeartsLost = permanentHeartsLost;
        this.activeSymptomIds = new ArrayList<>(activeSymptomIds);
        this.symptomTicksRemaining = symptomTicksRemaining;
    }

    public static final Codec<InfectionState> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.STRING.optionalFieldOf("disease_id").forGetter(s -> Optional.ofNullable(s.diseaseId)),
            Codec.INT.optionalFieldOf("incubation_ticks_remaining", 0).forGetter(s -> s.incubationTicksRemaining),
            Codec.INT.optionalFieldOf("ticks_remaining", 0).forGetter(s -> s.ticksRemaining),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("immunities", Map.of()).forGetter(s -> s.immunities),
            Codec.INT.optionalFieldOf("infection_count", 0).forGetter(s -> s.infectionCount),
            Codec.INT.optionalFieldOf("permanent_hearts_lost", 0).forGetter(s -> s.permanentHeartsLost),
            Codec.list(Codec.STRING).optionalFieldOf("active_symptom_ids", List.of()).forGetter(s -> s.activeSymptomIds),
            Codec.INT.optionalFieldOf("symptom_ticks_remaining", 0).forGetter(s -> s.symptomTicksRemaining)
    ).apply(inst, InfectionState::new));

    // Has any stage of disease (exposed OR infectious)
    public boolean isInfected() { return diseaseId != null; }
    // Incubating — has disease but not yet contagious/symptomatic
    public boolean isExposed() { return diseaseId != null && incubationTicksRemaining > 0; }
    // Contagious and symptomatic
    public boolean isInfectious() { return diseaseId != null && incubationTicksRemaining <= 0; }

    public String getDiseaseId() { return diseaseId; }
    public int getIncubationTicksRemaining() { return incubationTicksRemaining; }
    public int getTicksRemaining() { return ticksRemaining; }
    public int getInfectionCount() { return infectionCount; }
    public int getPermanentHeartsLost() { return permanentHeartsLost; }
    public boolean hasSymptoms() { return !activeSymptomIds.isEmpty() && symptomTicksRemaining > 0; }
    public List<String> getActiveSymptomIds() { return activeSymptomIds; }
    public int getSymptomTicksRemaining() { return symptomTicksRemaining; }

    // -- immunity ------------------------------------------------------------

    public boolean isImmuneTo(String diseaseId) {
        return diseaseId != null && immunities.containsKey(diseaseId);
    }

    public boolean hasAnyImmunity() { return !immunities.isEmpty(); }

    public int getImmunityTicksRemaining(String diseaseId) {
        return immunities.getOrDefault(diseaseId, 0);
    }

    /** Live view for status readouts, ordered for stable display. */
    public Map<String, Integer> getImmunities() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(immunities));
    }

    public void grantImmunity(String diseaseId, int durationTicks) {
        if (diseaseId != null && durationTicks > 0) immunities.put(diseaseId, durationTicks);
    }

    public void clearImmunity(String diseaseId) { immunities.remove(diseaseId); }
    public void clearAllImmunity() { immunities.clear(); }

    /** @return true if at least one immunity expired, so callers know to resync. */
    public boolean tickImmunity() { return tickImmunity(1); }

    public boolean tickImmunity(int amount) {
        if (immunities.isEmpty()) return false;
        boolean expired = false;
        var it = immunities.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            int left = entry.getValue() - amount;
            if (left <= 0) { it.remove(); expired = true; }
            else entry.setValue(left);
        }
        return expired;
    }

    // -- infection lifecycle -------------------------------------------------

    public void infect(String id, int incubationTicks, int durationTicks) {
        this.diseaseId = id;
        this.incubationTicksRemaining = incubationTicks;
        this.ticksRemaining = durationTicks;
    }

    public void tickIncubation() { if (incubationTicksRemaining > 0) incubationTicksRemaining--; }
    public void tickIncubation(int amount) { incubationTicksRemaining = Math.max(0, incubationTicksRemaining - amount); }
    public void tickInfection() { if (ticksRemaining > 0) ticksRemaining--; }
    public void tickInfection(int amount) { ticksRemaining = Math.max(0, ticksRemaining - amount); }
    public void tickSymptoms() { if (symptomTicksRemaining > 0) symptomTicksRemaining--; }

    public void setSymptoms(List<String> ids, int durationTicks) {
        this.activeSymptomIds = new ArrayList<>(ids);
        this.symptomTicksRemaining = durationTicks;
    }
    public void clearSymptoms() { activeSymptomIds.clear(); symptomTicksRemaining = 0; }

    /** Immunity is granted against the disease just recovered from, not universally. */
    public void recover(int immunityDurationTicks) {
        infectionCount++;
        grantImmunity(diseaseId, immunityDurationTicks);
        diseaseId = null;
        incubationTicksRemaining = 0;
        ticksRemaining = 0;
        clearSymptoms();
    }

    public void clearInfection() {
        diseaseId = null;
        incubationTicksRemaining = 0;
        ticksRemaining = 0;
        clearSymptoms();
    }

    public void recordPermanentHeartLoss() { permanentHeartsLost++; }
    public float permanentLossChance() { return Math.min(0.10f * (infectionCount + 1), 0.90f); }
}
