
# Infection Mod — Feature Overview

A Fabric mod for Minecraft 1.21.4 that simulates infectious disease dynamics between players and animals.

---

## Diseases

The mod has four diseases modelled on real-world pathogens. Each uses epidemiological parameters (R₀, CFR, incubation period, transmission probability) drawn from the literature.

### 1. Respiratory Fever *(COVID-like, R₀ ≈ 2.5)*

| Property | Value |
|---|---|
| Disease ID | `respiratory_fever` |
| Transmission rate (3 blocks) | 3% per second |
| Incubation period | 72,000 ticks (3 MC days) |
| Infectious duration | 120,000 ticks (5 MC days) |
| Case fatality rate | 1% |
| Immunity after recovery | 72,000 ticks (3 MC days) — waning |
| Reservoir hosts | Bats, Pigs, Cows, Chickens, Sheep, Foxes, Wolves, Cats, Villagers, Horses, Donkeys, Mules, Rabbits |

### 2. Scarlet Blight *(Measles-like, R₀ ≈ 12)*

| Property | Value |
|---|---|
| Disease ID | `scarlet_blight` |
| Transmission rate (3 blocks) | 90% per second |
| Incubation period | 120,000 ticks (5 MC days) |
| Infectious duration | 168,000 ticks (7 MC days) |
| Case fatality rate | 0.2% |
| Immunity after recovery | 2,400,000 ticks (100 MC days) — near-lifelong |
| Reservoir hosts | Villagers only (human-specific disease) |

### 3. Frost Sickness *(Influenza-like, R₀ ≈ 1.3)*

| Property | Value |
|---|---|
| Disease ID | `frost_sickness` |
| Transmission rate (3 blocks) | 20% per second |
| Incubation period | 24,000 ticks (1 MC day) |
| Infectious duration | 72,000 ticks (3 MC days) |
| Case fatality rate | 0.5% |
| Immunity after recovery | 24,000 ticks (1 MC day) — seasonal/short |
| Reservoir hosts | Pigs, Chickens |

### 4. Wasting Curse *(Prion/CJD-like, R₀ ≈ 0.001)*

| Property | Value |
|---|---|
| Disease ID | `wasting_curse` |
| Transmission rate (3 blocks) | 0.1% per second (effectively zero airborne) |
| Incubation period | 120,000 ticks (5 MC days) |
| Infectious duration | 240,000 ticks (10 MC days) |
| Case fatality rate | 95% |
| Immunity after recovery | None |
| Reservoir hosts | Cows only (BSE model) |

---

## Disease Stages

Each disease progresses through stages:

1. **Susceptible** — healthy, can be infected
2. **Exposed (Incubating)** — has the disease but is not yet contagious or symptomatic; HUD shows *"Exposed: [Disease]"* with a countdown to becoming infectious
3. **Infectious** — contagious and symptomatic; health penalty active; HUD shows *"Infection: [Disease]"* countdown
4. **Recovered** — immune for the disease's immunity duration; CFR roll happens here (fatal outcome → death; survival → possible permanent heart loss)

---

## Transmission Mechanics

Every second, the server scans infected (infectious-stage only) entities and attempts to spread disease to nearby susceptible targets:

- **3-block radius:** base transmission rate per second (varies by disease)
- **6-block radius:** base rate ÷ 6 (minimum 0.1%)
- **Direct contact (bounding boxes overlap):** base rate × 2, capped at 100%

Eligible targets:
- **Players** — can receive any disease
- **Reservoir host animals** — can only receive diseases they are listed as hosts for

Spread is bidirectional: player→animal, animal→player, animal→animal.

---

## Reservoir Hosts & Spawn Infection

Each animal mob type is a reservoir host for one or more diseases. When a mob first enters the world (fresh spawn only, not chunk reload), it has a **5% chance of spawning already infected** with one of the diseases it can host.

If a mob type is a host for multiple diseases, the spawn disease is chosen randomly among them.

---

## Effects on Players

When a player becomes **infectious** (after the incubation period):

- **Temporary health reduction:** −4 HP (2 hearts) for the duration
- HUD shows two **purple heart outlines** where the lost hearts would be

When a player **recovers**:

- **CFR roll** — if the disease's case fatality rate triggers, the player is killed (Disease 4 triggers 95% of the time)
- If the player survives, there is a chance of **permanent heart loss** (1 heart, 2 HP). Starts at 10% on first infection, +10% each time, max 90%
- The player gains immunity for the disease's immunity duration (Disease 4 grants no immunity)

Permanent losses are shown as **dark grey heart outlines** on the HUD, stacking from the right. These persist through death and logout.

---

## HUD Overlay

**During incubation (Exposed stage):**

- **Top-center:** `Exposed: [Disease Name]` (orange-yellow text)
- **Below:** `Infectious in: M:SS` countdown

**During infectious stage:**

- **Purple heart outlines** for the 2 temporarily lost hearts
- **Top-center:** `Infection: [Disease Name]` (red text)
- **Below:** `Clears in: M:SS` countdown
- **Symptom line** (if symptoms rolled): `Symptoms: Fatigue, Nausea, Weakness` (orange)

**Always visible:**

- **Dark grey heart outlines** for permanent heart losses (stacked from the right)

---

## Debug Commands

All commands require operator permission (level 2).

| Command | Description |
|---|---|
| `/infect [player] [disease]` | Infects yourself or a named player with the specified disease (defaults to `respiratory_fever`) |
| `/recover [player]` | Clears an active infection from yourself or a named player |
| `/infection-status` | Lists all infected, immune, or permanently-damaged entities within 50 blocks |
| `/infection-stats [disease]` | Epidemic report: case counts, observed R, outcomes, live S/E/I/R, and a per-MC-day case curve |
| `/infection-stats reset` | Clears the epidemic log |

Disease IDs for `/infect`: `respiratory_fever`, `scarlet_blight`, `frost_sickness`, `wasting_curse`

`/infection-status` output format:

```
Cow: EXPOSED (wasting_curse) infectious in 85420s
Villager: INFECTIOUS (scarlet_blight) 12340s remaining
Sheep: IMMUNE 4821s remaining
Player: perm hearts lost: 2
```

---

## Measuring an Outbreak

`EpidemicLog` records every infection (with its source, so transmission chains are
reconstructable) and every resolved case. `/infection-stats` turns that into:

- **Cases** — total, split into index cases (spawn-seeded or `/infect`) and secondary
  cases (caught from another entity)
- **Observed R** — mean secondary infections per case, counted **only over cases whose
  infectious period has finished**. Including still-infectious cases would drag the mean
  down every tick and never settle. Reads `n/a` until the first case resolves.
- **Live S/E/I/R** — current susceptible / exposed / infectious / immune counts across
  loaded chunks for that disease's eligible population
- **Case curve** — infections per MC day as a histogram, so you can see whether an
  outbreak is climbing, peaking, or burnt out

The log is in-memory and clears on server restart — an epidemic is something you observe
within a session.

Infectious entities emit a visible particle signature: red spores for Scarlet Blight (the
rash), sneeze puffs for Crimson Fever, ash for Wasting Curse. Airborne diseases also emit
an occasional sneeze at head height. Exposed (incubating) entities emit nothing — they are
not yet contagious and should not be identifiable on sight.

---

## Technical Notes

- **Platform:** Fabric Loader 0.19.2, Fabric API 0.119.4+1.21.4, Minecraft 1.21.4
- **Infection state** is stored per-entity using Fabric Data Attachments and persists to disk via a Codec
- **Server-to-client sync** uses a custom `InfectionSyncPayload` packet sent every second while infected/exposed, and on state changes
- Animal infection state lives entirely in the entity's persisted attachment. `tickAnimals`
  walks each world's own entity list rather than a tracking set, so being loaded *is* the
  registration — progression survives chunk reloads and server restarts with no bookkeeping.
  (A shared UUID set could not work here: `END_WORLD_TICK` fires once per dimension, so
  every foreign dimension's pass would see the entity as missing.)
- Animals are not synced to clients; their status is conveyed by particles, not a HUD
- The incubation stage is tracked server-side per entity; animals in incubation are not yet contagious (they become a spread source only after incubation ends)
