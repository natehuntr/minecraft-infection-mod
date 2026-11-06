# Development Notes

Working notes for contributors. [INFECTION_MOD.md](INFECTION_MOD.md) describes *what* the
mod does; this describes how to work on it, how to test it, and the traps already fallen
into once.

---

## Building and running

Java 21, Fabric Loom. The usual loop is:

```bash
gradle runClient
```

**The Gradle wrapper is not committed** — only `gradle/wrapper/gradle-wrapper.properties`.
`gradlew` and `gradle-wrapper.jar` are absent, so a fresh clone cannot run `./gradlew`.
Either use a system Gradle (8.x or 9.x both work with Loom 1.9.2) or regenerate the wrapper
once with `gradle wrapper`.

### Cloud sessions cannot compile this project

`maven.fabricmc.net` is blocked by the Claude Code cloud egress policy, so Loom cannot
bootstrap and **no cloud session can compile or run the mod**. Anything written there is
unverified until built locally. Two consequences:

- Build and test on a local machine, not in a cloud session.
- Cloud sessions also cannot push to GitHub (403 on writes), so changes come back as a
  `git bundle`: `git fetch <bundle> <branch>` then `git checkout -B <branch> FETCH_HEAD`.
  Note that `checkout -B` discards uncommitted local changes to tracked files — commit or
  stash local test tweaks first.

### Checking API signatures without compiling

Minecraft's client rendering API changes often, and guessing wrong costs a build cycle.
The yarn mappings are readable even when the Maven host is blocked:

```
https://raw.githubusercontent.com/FabricMC/yarn/1.21.4/mappings/net/minecraft/<path>.mapping
```

Those files give the real method names and descriptors. Prefer them over memory for
anything in `net.minecraft.client.render`.

---

## Testing

Default disease timings are realistic and therefore useless for testing — Disease B's
incubation alone is 5 MC days (100 real minutes). For a fast full cycle, temporarily change
the three timing arguments in `DiseaseRegistry.java`:

```java
// incubation, duration, prodrome
new Disease("disease_b", "Disease B", 0.99f, 0.05f, 200, 1200, 600, 0.002f, 2400000, 20, 120),
```

That gives 10s exposed → 30s prodromal → 30s rash → recovered. **Revert before committing.**

Setting incubation short while leaving duration at its default is a trap: you go infectious
in 10 seconds and then wait 70 real minutes for the rash, which looks like the prodrome is
broken.

### Recipes

```
/gamerule doMobSpawning false     # control the population
/infect @e[type=villager,limit=3] disease_b
/infection-status                 # PRODROMAL vs INFECTIOUS, per-entity timers
/infection-stats disease_b        # cases, observed R, S/E/I/R, case curve
/infection-stats reset            # clear the epidemic log
```

| Mechanic | How to confirm it works |
|---|---|
| Timers advance | `/infection-status` twice ~10s apart; seconds should drop by ~10 |
| Prodrome | Villager reads `PRODROMAL` with no rash texture and no particles |
| Rash onset | Flips to `INFECTIOUS`, texture swaps, crimson spores appear |
| Aerosol | Infect a villager in a room, kill it, stand where it was — you can still catch it. `/infection-stats` shows the contaminated-block count |
| Per-disease immunity | Recover from one disease, then confirm you can still catch another |
| Off-screen spread | Seed a village, walk 100+ blocks away, return — case count should have grown |
| Infected Beef | Kill a cow while it is `INFECTIOUS` with `disease_c` |
| Death messages | `/infect @s disease_c` and wait out the illness — 95% CFR, names the disease |

Raise `spawnInfectionChance` (fourth argument) to `1.0f` to make every spawn infected when
you need a population quickly.

---

## Pitfalls

Each of these was a real bug, not a hypothetical.

**Per-dimension ticking plus global state.** `ServerTickEvents.END_WORLD_TICK` fires once
per dimension. A global `Set<UUID>` of infected entities therefore gets a `world.getEntity`
miss on every *foreign* dimension's pass — the Nether's tick looked up an overworld cow, got
null, and dropped it from tracking. Animal disease silently never progressed. Anything keyed
by entity must either be per-world or be driven off the world's own entity list. The latter
is what `tickAnimals` does now, and it makes chunk reloads and restarts work for free.

**Item registration needs its registry key (1.21.4).** `new Item(new Item.Settings())`
throws `ExceptionInInitializerError: Item id not set` at startup. The key must be in the
settings: `new Item(new Item.Settings().registryKey(key))`.

**Renderers no longer see the entity (1.21.2+).** `getTexture` receives a render state, not
the entity. To make a texture depend on entity state without a mixin, subclass the renderer
and override `createRenderState()` *covariantly* to return your own subclass of the state;
`updateRenderState` still receives the entity, so that is where you copy the flag across.
See `InfectedVillagerRenderer`.

**Attribute modifiers and status effects outlive `InfectionState`.** Clearing the state does
not remove the max-health modifier or any symptom effects — those live on the player. A path
that ends an infection must undo all three, which is what `InfectionManager.cure` is for.
`reapplyOnLogin` reconciles in both directions so a missed cleanup cannot strand a modifier.

**Transmission scope must match progression scope.** When spread was gathered from
player proximity while progression ran world-wide, outbreaks you walked away from ticked to
recovery without infecting anyone. Any measured R described where the player stood.

**Docs drift silently.** `INFECTION_MOD.md` described two diseases that had been deleted,
for many commits, because the commit that removed them touched only Java. If a change alters
observable behaviour, the doc is part of the change.

---

## Design decisions

**Observed R counts only completed cases.** `EpidemicLog.stats` divides secondary infections
by the number of cases whose infectious period has *finished*. Including still-infectious
cases would drag the mean down every tick and never settle, so it reads `n/a` until the
first case resolves.

**Aerosol writes one block, reads twenty-seven.** Sources deposit only where they stand;
targets sample the 3×3×3 around themselves. The reverse — each source painting a volume —
costs far more writes for a volume the host may immediately leave.

**Villager appearance is a snapshot, not deltas.** `VillagerInfectionPayload` sends the full
list of visibly-diseased villagers each second. It cannot drift out of sync from a dropped
packet or an entity being untracked and retracked.

**Prodrome is derived, not stored.** Rash onset is computed from `ticksRemaining` against
the disease's `prodromeTicks`, on both server and client, so there is no extra state to
persist or sync and no risk of the two disagreeing.

**Particles are `SimpleParticleType` constants only.** `DustParticleEffect` would give exact
colours but changed signature in 1.21.2. Since cloud sessions cannot compile, version-stable
constants were chosen deliberately over nicer colours.

---

## Save compatibility

State lives in Fabric Data Attachments, persisted via `InfectionState.CODEC`. Two changes so
far have broken older saves, neither fatally:

- **Per-disease immunity** replaced the `immune` / `immunity_ticks_remaining` fields with an
  `immunities` map. Older saves lose immunity data; entities simply become susceptible.
- **The A/B/C rename** changed disease ids. Entities carrying `crimson_fever`,
  `scarlet_blight` or `wasting_curse` now resolve to a null `Disease`. Those infections stop
  spreading and run down to recovery with no immunity rather than crashing, and stale
  immunity keys expire normally. A world mid-outbreak goes quiet.

Unknown disease ids are handled defensively throughout, so adding or renaming a disease
degrades rather than crashes. Worth preserving.

---

## Limits

| | |
|---|---|
| `EpidemicLog` | 50,000 infections and 50,000 resolutions, then silently stops recording |
| `AerosolTracker` | 20,000 contaminated blocks per dimension, then silently stops depositing |

Both are in-memory and cleared on server stop — an epidemic is something you observe within
a session.
