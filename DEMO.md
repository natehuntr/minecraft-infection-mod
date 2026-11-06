# Demo Build

This branch (`demo/fast-timings`) is the development code with disease timings compressed
so that a full infection cycle fits into a live presentation. **Do not merge it back** —
the only differences are the timing numbers in `DiseaseRegistry.java` and this file.

| | Incubation | Contagious, no visible sign | Visible | Afterwards |
|---|---|---|---|---|
| Disease A | 10 s | 20 s | 40 s | Immune for 2 min |
| Disease B | 10 s | 30 s | 30 s (rash, villager texture change) | Immune effectively for good |
| Disease C | 10 s | — | 60 s | 95% die; no immunity |

Transmission curves and aerosol lifetimes are unchanged, so spread *per second of contact*
behaves exactly as in the real build — only the length of each stage is shorter.

**Caveat for `/infection-stats`:** the case curve is bucketed per Minecraft day, so with
these timings a whole outbreak lands in one or two buckets. Show observed R and the S/E/I/R
counts rather than the curve.

---

## Before the meeting

1. `gradle runClient` on this branch. If it does not build, fall back to `main`.
2. Create a **new** world (old saves use old disease ids). A plains-heavy seed or a
   single-biome plains world puts a village near spawn.
3. Find a village, then copy the world folder as a backup to restore if anything goes
   wrong live.
4. Rehearse the script once end to end.

## Script (~5 minutes)

```
/gamerule doMobSpawning false
/gamerule doDaylightCycle false
/infection-stats reset
/infect @e[type=villager,limit=1,sort=nearest] disease_b
```

1. **Invisible spread (0:00–0:40).** Run `/infection-status` every ~10 s. The index case goes
   `EXPOSED` → `INFECTIOUS` while looking completely normal, and neighbours start appearing
   as `EXPOSED`. Talking point: Disease B is contagious before anyone can see it.
2. **The rash (0:40–1:10).** The index villager's texture changes and crimson spores appear.
   By now the people it infected are already incubating.
3. **The numbers.** `/infection-stats disease_b` — cases, observed R, live S/E/I/R.
4. **Air stays infectious.** Infect a villager in an enclosed room, kill it, then stand
   where it stood: you can still catch it for ~2 minutes.
   `/infection-stats` shows the contaminated-block count.
5. **Optional — a player case.** `/infect @s disease_a` shows the HUD, the lost hearts and
   the countdown. `/recover @s` ends it.

Reset between runs with `/recover @e` and `/infection-stats reset`.
