# Infection Mod — Feature Overview

A Fabric mod for Minecraft 1.21.4 that simulates infectious disease dynamics between
players, animals, and villagers.

---

## Diseases

Three diseases modelled on real-world pathogens, parameterised from the epidemiological
literature (R₀, CFR, incubation period, transmission curve).

### 1. Crimson Fever *(COVID-like, R₀ ≈ 2.5)*

| Property | Value |
|---|---|
| Disease ID | `crimson_fever` |
| Transmission curve | P(t) = 80% × (1 − e^−ᵗ⁄τ), τ = 60 s |
| Incubation period | 72,000 ticks (3 MC days) |
| Infectious duration | 120,000 ticks (5 MC days) |
| Prodrome (contagious, invisible) | 24,000 ticks (1 MC day) |
| Aerosol lifetime | 30 s |
| Case fatality rate | 1% |
| Immunity after recovery | 72,000 ticks (3 MC days) — waning |
| Spawn infection chance | 5% per eligible mob |
| Reservoir hosts | Bats, Pigs, Cows, Chickens, Sheep, Foxes, Wolves, Cats, Villagers, Horses, Donkeys, Mules, Rabbits |

### 2. Scarlet Blight *(Measles-like, R₀ ≈ 12)*

| Property | Value |
|---|---|
| Disease ID | `scarlet_blight` |
| Transmission curve | P(t) = 99% × (1 − e^−ᵗ⁄τ), τ = 20 s |
| Incubation period | 120,000 ticks (5 MC days) |
| Infectious duration | 168,000 ticks (7 MC days) |
| Prodrome (contagious, invisible) | 84,000 ticks (3.5 MC days — half the infectious window) |
| Aerosol lifetime | 120 s |
| Case fatality rate | 0.2% |
| Immunity after recovery | 2,400,000 ticks (100 MC days) — near-lifelong |
| Spawn infection chance | 5% per villager |
| Reservoir hosts | Villagers only (human-specific disease) |

### 3. Wasting Curse *(Prion/CJD-like, R₀ ≈ 0.001)*

| Property | Value |
|---|---|
| Disease ID | `wasting_curse` |
| Transmission (airborne) | Flat 0.1% per second (τ = 0 disables the curve) |
| Incubation period | 120,000 ticks (5 MC days) |
| Infectious duration | 240,000 ticks (10 MC days) |
| Prodrome | None — visible as soon as infectious |
| Aerosol lifetime | None (prions are not airborne) |
| Case fatality rate | 95% |
| Immunity after recovery | None |
| Spawn infection chance | 1% per cow |
| Reservoir hosts | Cows only (BSE model) |

---

## Disease Stages

1. **Susceptible** — healthy, can be infected
2. **Exposed (Incubating)** — carries the disease but is not yet contagious or symptomatic;
   HUD shows *"Exposed: [Disease]"* with a countdown to becoming infectious
3. **Prodrome** — contagious, health penalty active, but **no visible sign**. HUD shows
   *"Unwell: [Disease] — Contagious, rash in M:SS"*. Other entities look completely healthy.
4. **Rash** — contagious and visibly ill; particles and the villager texture appear. HUD
   shows *"Infection: [Disease]"* countdown
5. **Recovered** — immune for that disease's immunity duration. The CFR roll happens here:
   a fatal outcome kills, survival may cost a permanent heart.

The prodrome is the point: **being contagious and looking contagious are different things.**
A villager spreads Scarlet Blight for 3.5 MC days before anything shows, so an outbreak is
already well underway by the time the first rash appears. `/infection-status` distinguishes
`PRODROMAL` from `INFECTIOUS`; nothing in-world does.

Immunity is tracked **per disease**. Recovering from Scarlet Blight grants no protection
against Crimson Fever or Wasting Curse. A fatal case names the disease in the death message
("Steve succumbed to Scarlet Blight") rather than a bare "Steve died".

---

## Transmission Mechanics

Every second the server scans **every infectious entity in loaded chunks** — not only those
near a player — and attempts to spread to nearby susceptible targets using an
**exposure-time saturation curve**:

```
P(t) = maxP × (1 − e^−ᵗ⁄τ)
```

where *t* is accumulated exposure in seconds and *τ* is the disease's half-life constant.
The marginal probability during each second of exposure is the increment along that curve:

```
ΔP = P(t_new) − P(t_prev)
```

Proximity modifies the **accumulation rate**, not τ:

| Range | Accumulation rate |
|---|---|
| Direct contact (bounding boxes overlap) | 2.0 s/s |
| Close range (≤ 3 blocks) | 1.0 s/s |
| Medium range (3–6 blocks) | 1/6 s/s |

Exposure decays at **2 s/s** once entities leave range, so brief separations matter less
than sustained ones. A pair that successfully transmits has its counter cleared.

**Wasting Curse** uses a flat model (τ = 0): probability is `maxP × rate` per second,
independent of exposure duration. Its real vector is food, not air.

### Aerosol persistence

Contagious hosts exhale into the block they occupy once a second, and that block stays
infectious for the disease's aerosol lifetime after they leave — so a room can infect you
when nobody is in it. Targets sample the 3×3×3 around themselves and take the freshest
contamination found.

Freshness decays linearly from deposit to expiry and is used directly as the accumulation
rate, so air just breathed is as dangerous as standing beside the host, fading to nothing as
it settles. Aerosol exposure runs through the same saturation curve, keyed per
(disease, target) rather than (source, target) — the host may be long gone, or dead. Their
UUID rides along on the cloud only so the case can still be attributed in the epidemic log.

Scarlet Blight's 120 s comes from measles staying airborne roughly two real hours, which is
about 100 s once compressed into Minecraft's 20-minute day.

Eligible targets are players (any disease) and that disease's reservoir hosts. Spread is
bidirectional: player→animal, animal→player, animal→animal.

---

## Reservoir Hosts & Spawn Infection

When a mob first enters the world (fresh spawn only, not chunk reload) it gets a per-disease
roll to spawn already infected: 5% for Crimson Fever and Scarlet Blight, 1% for Wasting
Curse. If a mob hosts several diseases, the first successful roll wins.

**Newborns are never seeded.** Babies start susceptible, so breeding restores susceptibles to
a village rather than manufacturing new index cases — which matters because births are the
only mechanism by which a village that has burnt through an outbreak becomes vulnerable
again.

---

## Animal Disease Progression

Infected animals progress incubation → infectious → recovery or death, ticked once per
second from each world's own entity list.

- **Wasting Curse (95% CFR)** — when a cow's illness ends the CFR roll usually kills it,
  dropping **Infected Beef** alongside normal loot. Killing a cow that is *already
  infectious* also drops Infected Beef, so the food route is reachable in ordinary play
  rather than requiring the full 15-day course to run uninterrupted.
- **Crimson Fever / Scarlet Blight** — low CFR; animals usually recover and gain immunity.

---

## Effects on Players

While **infectious**: −4 max HP (2 hearts), shown as two purple heart outlines.

On **recovery**:
- CFR roll — a fatal outcome kills the player
- Survivors risk **permanent heart loss** (1 heart). 10% on the first infection, +10% each
  subsequent one, capped at 90%
- Immunity is granted against that disease only (Wasting Curse grants none)

Permanent losses show as dark grey heart outlines stacking from the right, and persist
through death and logout.

---

## Seeing Infection

Visible signs are **Scarlet Blight only** — its rash is the one disease whose real-world
signature is visible across a room. Crimson Fever and Wasting Curse are invisible and must
be found with `/infection-status` or inferred from their effects.

- **Particles** — crimson spores across the body, plus an occasional sneeze at head height
- **Villager texture** — infected villagers swap to a diseased skin at
  `assets/infection_mod/textures/entity/villager/infected_villager.png`. Profession and
  biome clothing are separate feature renderers and still draw on top, so an infected farmer
  still reads as a farmer.

Both appear only once an entity is **infectious**. Incubating entities look completely
normal — they are not yet contagious and must not be identifiable on sight. When the
prodrome stage is added, the rash will begin at rash onset rather than at infectiousness,
opening the window where a villager spreads disease while still looking healthy.

---

## Measuring an Outbreak

`EpidemicLog` records every infection (with its source, so transmission chains are
reconstructable) and every resolved case. `/infection-stats` reports:

- **Cases** — total, split into index cases (spawn-seeded or `/infect`) and secondary cases
- **Observed R** — mean secondary infections per case, counted **only over cases whose
  infectious period has finished**. Including still-infectious cases would drag the mean
  down every tick and never settle. Reads `n/a` until the first case resolves.
- **Live S/E/I/R** — susceptible / exposed / infectious / immune counts for that disease's
  eligible population, across **loaded chunks in your current dimension only**
- **Case curve** — infections per MC day, showing whether an outbreak is climbing or spent

The log is in memory and clears on server restart — an epidemic is something you observe
within a session.

---

## Debug Commands

All commands require operator permission (level 2).

| Command | Description |
|---|---|
| `/infect [targets] [disease]` | Infects any entity selector — e.g. `/infect @e[type=villager,limit=3] scarlet_blight`. Defaults to you with `crimson_fever`. |
| `/recover [targets]` | Clears active infections from any entity selector |
| `/infection-status` | Lists infected, immune, or permanently-damaged entities within 50 blocks |
| `/infection-stats [disease]` | Epidemic report: cases, observed R, outcomes, live S/E/I/R, case curve |
| `/infection-stats reset` | Clears the epidemic log |

Disease IDs: `crimson_fever`, `scarlet_blight`, `wasting_curse`

```
Cow: EXPOSED (wasting_curse) infectious in 85420s
Villager: INFECTIOUS (scarlet_blight) 12340s remaining
Sheep: IMMUNE crimson_fever 4821s
Player: perm hearts lost: 2
```

---

## Technical Notes

- **Platform:** Fabric Loader 0.19.2, Fabric API 0.119.4+1.21.4, Minecraft 1.21.4
- **Infection state** is per-entity via Fabric Data Attachments, persisted through a Codec
- **Player sync** uses `InfectionSyncPayload`, sent each second while infected and on state
  changes. **Villager appearance** uses `VillagerInfectionPayload`, a once-a-second snapshot
  of which nearby villagers show the rash — a full list rather than deltas, so it cannot
  drift out of sync on a dropped packet.
- **Animal ticking** walks each world's own entity list rather than a tracking set. Being
  loaded *is* the registration, so progression survives chunk reloads and restarts with no
  bookkeeping. A shared UUID set could not work: `END_WORLD_TICK` fires once per dimension,
  so every foreign dimension's pass would see the entity as missing and drop it.
- **Texture swapping** avoids mixins. Since 1.21.2 `getTexture` receives a render state
  rather than the entity, so `InfectedVillagerRenderer` subclasses the villager renderer and
  overrides `createRenderState()` covariantly to supply an extended state carrying one extra
  flag; `updateRenderState` still receives the entity and sets it.

### Known gaps

- Transmission is saturated: at close range ~84 seconds of contact is already a 97.5%
  infection chance for Scarlet Blight, so proximity and duration barely discriminate and
  quarantine is close to a no-op. Needs re-tuning against measured R.
- Immunity is all-or-nothing per disease; no partial or waning protection.
- Aerosol is tracked per block with a hard cap of 20,000 blocks per dimension. A very large
  outbreak will silently stop depositing past that ceiling.
