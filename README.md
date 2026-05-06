# Infection Mod

A Fabric mod for Minecraft 1.21.5 that simulates infectious disease dynamics between players, animals, and villagers.

---

## Overview

Infection Mod is a STEM-focused, systems-based learning project embedded in Minecraft — one of the most widely used interactive platforms globally. Its goal is to build intuitive understanding of cause-and-effect relationships in disease prevention and health systems, including:

- **Timing** — when you act matters as much as whether you act
- **Partial protection** — vaccines reduce risk without eliminating it
- **Waning effects** — immunity is temporary and requires renewal
- **Tradeoffs** — protection has costs; inaction has costs too
- **Access and context** — prevention tools are not equally available to all
- **Community dynamics** — individual choices aggregate into population-level outcomes

Rather than persuading players toward any particular view, the mod asks a systems-level question: *why might people not access, accept, want, or be able to use prevention tools, and how do system structures shape those outcomes?* By embedding these dynamics as background game mechanics, players experience how disease systems behave over time — reinforcing the connection between individual choices and collective outcomes without messaging or advocacy.

---

## Current Features

### Disease: Crimson Fever

The mod currently implements one disease with the following properties:

| Property | Value |
|---|---|
| Transmission radius | 3 blocks (30% per second), 6 blocks (5% per second) |
| Direct contact transmission | 60% per second (bounding box overlap) |
| Duration | 48,000 ticks (~40 minutes / 2 day-night cycles) |
| Immunity after recovery | 24,000 ticks (~20 minutes / 1 day-night cycle) |
| Permanent heart loss | 10% on first infection, +10% per re-infection, max 90% |

### Reservoir Hosts

The following mob types can carry and spread Crimson Fever:

- **Passive animals:** Bats, Pigs, Cows, Chickens, Sheep, Rabbits
- **Neutral mobs:** Foxes, Wolves, Cats
- **Villagers:** Both carriers and recipients
- **Mounts:** Horses, Donkeys, Mules

Each reservoir host has a **5% chance of spawning already infected**. This rolls once on first entry into the world — mobs loaded from saved chunks are not re-rolled.

### Transmission Mechanics

Spread can occur in all directions:

- **Animal → Player**
- **Player → Animal**
- **Animal → Animal**
- **Player → Villager** (and vice versa)

Eligible targets are any susceptible entity (not already infected or immune) within range.

### Effects on Players

**While infected:**
- Maximum health reduced by 4 HP (2 hearts) for the duration
- HUD shows two purple heart outlines where the lost hearts would be
- Top-centre HUD text: `Infection: Crimson Fever`
- Countdown timer below: `Clears in: M:SS`

**On recovery:**
- Temporary health penalty removed
- Chance of permanent heart loss (one heart, 2 HP) based on re-infection count
- Permanent losses shown as dark grey heart outlines stacking from the right
- Immunity granted for 20 minutes — re-infection blocked during this window

Effects persist across logout/login and respawn. Permanent heart loss survives death; active infection is cleared on death.

### Debug Commands

All commands require operator permission (level 2).

| Command | Description |
|---|---|
| `/infect [player]` | Infects yourself or a named player with Crimson Fever immediately |
| `/recover [player]` | Clears an active infection from yourself or a named player |
| `/infectionstatus` | Lists all infected, immune, or permanently-damaged entities within 50 blocks |

Example `/infectionstatus` output:
```
Cow: INFECTED (crimson_fever) 342s remaining
Sheep: IMMUNE 4821s remaining
Player: perm hearts lost: 2
```

---

## Planned Features

Current next planned feature:
- Addition of a 30% chance of symptoms, which do not last the entirety of the disease: Fatigue (slowness potion), nausea, and weakness (reduced damage)
  - These symptoms will occur for anywhere between 3-20 minutes
  - There is a 1% chance that multiple symptoms will arise

Additional future features:
- Vaccinations, available at villages or at health huts, which are able to give resistance
  - Modelling specific vaccination dynamics, including mild side effects, decreasing immunity, and different requirements for dose amount and schedule
- Multiple diseases with distinct transmission vectors (airborne, waterborne, zoonotic), differing severity, virulence, and vaccine compatibility
- Health huts — randomly generated structures containing a Doctor NPC offering vaccination, and treatments for emeralds
- Village-level herd immunity tracking
- Mutation system altering transmissibility and immune evasion over time
- Waning immunity with configurable decay rates per disease

---

## Technical Details

| Component | Detail |
|---|---|
| Platform | Fabric Loader 0.19.2 |
| Fabric API | 0.145.1+26.1 |
| Minecraft | 1.21.5 |
| Infection state storage | Fabric Data Attachments, persisted to disk via Codec |
| Client sync | Custom `InfectionSyncPayload` packet sent every second while infected and on state changes |
| Animal infection state | Tracked in memory; persists across chunk reloads |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.5
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods` folder
3. Download the latest Infection Mod release and place it in your `mods` folder
4. Launch Minecraft using the Fabric profile

---

## Project Background

This project is motivated by real-world experience in disease prevention and the recognition that scientific efficacy alone does not guarantee population-level impact. Uptake, persistence, access, trust, and fit within daily life strongly shape health outcomes — and these are not individual failures but system-level phenomena.

By embedding prevention dynamics as Minecraft mechanics, players encounter these systems organically: a village with low vaccination coverage becomes a genuine hazard; brief exposure carries low risk while prolonged proximity compounds it; immunity wanes and requires renewal; the doctor costs emeralds you may not have. No conclusions are drawn for the player — the system simply behaves, and players experience the consequences of how it behaves.

---

## License

MIT License — see `LICENSE` for details.

---

## Contributing

Issues and pull requests are welcome. Please open an issue first to discuss significant changes.
