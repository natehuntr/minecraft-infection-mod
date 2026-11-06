# Modelling infection dynamics in Minecraft

A Fabric mod for Minecraft 1.21.4 that simulates infectious disease dynamics between players, animals, and villagers. 

---

## Overview

Infectious Disease Mod is a systems-based learning project embedded in Minecraft — one of the most widely used interactive platforms globally. Its goal is to build intuitive understanding of cause-and-effect relationships in disease prevention and health systems, including:

- **Timing** — when you act matters as much as whether you act
- **Partial protection** — vaccines reduce risk without eliminating it
- **Waning effects** — immunity is temporary and requires renewal
- **Tradeoffs** — protection has costs; inaction has costs too
- **Access and context** — prevention tools are not equally available to all
- **Community dynamics** — individual choices aggregate into population-level outcomes

Rather than persuading players toward any particular view, the mod asks a systems-level question: *why might people not access, accept, want, or be able to use prevention tools, and how do system structures shape those outcomes?* By embedding these dynamics as background game mechanics, players experience how disease systems behave over time — reinforcing the connection between individual choices and collective outcomes without explicit messaging or advocacy.

---

## Current Features

Three diseases, each parameterised from the epidemiological literature. Names are
placeholders while the mechanics are being tuned; each models a specific real pathogen.

| | Models | R₀ | Distinguishing mechanic |
|---|---|---|---|
| **Disease A** | COVID-like | ≈ 2.5 | Broad reservoir — 14 host species |
| **Disease B** | Measles-like | ≈ 12 | Contagious for days before any visible sign; air stays infectious after the host leaves |
| **Disease C** | Prion / CJD-like | ≈ 0.001 | Near-zero airborne spread; transmits through contaminated meat. 95% fatal |

Each disease runs through five stages — susceptible, exposed, prodrome, rash, recovered —
with exposure accumulating along a saturation curve rather than a flat per-second roll, so
brief contact is low-risk and sustained proximity compounds. Immunity is tracked per
disease. Animals progress independently of whether a player is watching.

**[INFECTION_MOD.md](INFECTION_MOD.md) is the detailed reference** — full parameters,
transmission maths, stage definitions, commands, and known gaps. It is kept in step with the
code; this section is deliberately a summary rather than a second copy of those numbers.

**[DEVELOPMENT.md](DEVELOPMENT.md)** covers building, testing recipes, and the pitfalls
worth knowing before changing the simulation.

---

## Planned Features

Current next planned feature:
- Vaccinations with specific dynamics including mild side effects, decreasing immunity, and different requirements for dose amount and schedule


Additional future features:
- Distrbution of vaccines within villages
- Waterborne transmission, and vaccine compatibility differing by disease
- Health huts — randomly generated structures containing a Doctor NPC offering vaccination, and treatments for emeralds
- Mutation system altering transmissibility and immune evasion over time
- Gradual waning of immunity, rather than the current fixed duration followed by full susceptibility
- Re-tuning the transmission curve against measured R — it currently saturates, so proximity and duration barely discriminate

---

## Technical Details

| Component | Detail |
|---|---|
| Platform | Fabric Loader 0.19.2 |
| Fabric API | 0.119.4+1.21.4 |
| Minecraft | 1.21.4 |
| Infection state storage | Fabric Data Attachments, persisted to disk via Codec |
| Client sync | `InfectionSyncPayload` for the player's own state; `VillagerInfectionPayload` for which nearby villagers are visibly diseased |
| Animal infection state | Driven off each world's entity list, so progression survives chunk reloads and restarts with no tracking state |
| Instrumentation | `EpidemicLog` records every infection with its source; `/infection-stats` reports observed R, live S/E/I/R, and the case curve |

---

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.4
2. Download [Fabric API](https://modrinth.com/mod/fabric-api) and place it in your `mods` folder
3. Download the latest Infection Mod release and place it in your `mods` folder
4. Launch Minecraft using the Fabric profile

---

## Project Background

This project is motivated by real-world experience in disease prevention and the recognition that scientific efficacy alone does not guarantee population-level impact. Uptake, persistence, access, trust, and fit within daily life strongly shape health outcomes — and these are not individual failures but system-level phenomena.

By embedding prevention dynamics as Minecraft mechanics, players encounter these systems organically: a village with low vaccination coverage becomes a genuine hazard; brief exposure carries low risk while prolonged proximity compounds it; immunity wanes and requires renewal; the doctor costs emeralds you may not have. No conclusions are drawn for the player — the system simply behaves, and players experience the consequences of how it behaves.

---

## Contributing

Issues and pull requests are welcome. Please open an issue first to discuss significant changes.
