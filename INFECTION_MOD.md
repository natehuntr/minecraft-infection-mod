
A Fabric mod for Minecraft 1.21.4 that simulates infectious disease dynamics between players and animals.

---

## Disease: Crimson Fever

The mod currently has one disease, **Crimson Fever**, with the following properties:

| Property | Value 
|---|---|
| Transmission rate | 30% chance per second within 3 blocks |
| Duration | 48,000 ticks (40 minutes, or two full day/night cycles) |
| Immunity after recovery | 24,000 ticks (20 minutes, or one full day/night cycle) |
| Permanent heart loss chance | Scales with re-infection count (10% first time, +10% each time, max 90%) |

---

## Reservoir Hosts (Animal Carriers)

The following mob types can carry and spread the disease:

- Bats, Pigs, Cows, Chickens, Sheep

- Foxes, Wolves, Cats, Rabbits

- Villagers

- Horses, Donkeys, Mules

**Spawn infection:** Each reservoir host has a **5% chance of spawning already infected**. This only rolls once — when the mob first enters the world. Mobs loaded from saved chunks are not re-rolled.

---

## Transmission Mechanics

Every second, the server scans for infected entities and attempts to spread disease to nearby susceptible targets:

- **Spread radius:** 3 blocks, for a 30% chance of spread. 6 blocks, for a 5% chance of spread.

- **Transmission chance:** 30% per second (60% if bounding boxes overlap — i.e. direct contact)

- **Eligible targets:** Players and any reservoir host mob that is not already infected or immune

- Spread can happen **player → animal**, **animal → player**, and **animal → animal**

---

## Effects on Players

When a player is infected:

- **Temporary health reduction:** Maximum health is reduced by 4 HP (2 hearts) for the duration of the infection

- The HUD shows two **purple heart outlines** where the lost hearts would be

When a player recovers:

- The temporary health penalty is removed

- There is a chance of **permanent heart loss** (one heart, 2 HP). This chance starts at 10% on the first infection and increases by 10% with each subsequent infection, capping at 90%

- The player gains **immunity** for 20 minutes, during which they cannot be re-infected

- Permanent losses are shown as **dark grey heart outlines** on the HUD, stacking from the right

Effects persist across logout/login and respawn (permanent losses survive death; active infection is cleared on death).

---

## HUD Overlay

While infected, the player sees:

- **Purple heart outlines** for the 2 temporarily lost hearts

- **Dark grey heart outlines** for any permanently lost hearts (stacked from the right)

- **Top-center text:** `Infection: Crimson Fever`

- **Below that:** `Clears in: M:SS` countdown timer

The tinted hearts render above the vanilla health bar so they are always visible.

---

## Debug Commands

All commands require operator permission (level 2).

| Command             | Description                                                                  |
| ------------------- | ---------------------------------------------------------------------------- |
| `/infect [player]`  | Infects yourself or a named player with Crimson Fever immediately            |
| `/recover [player]` | Clears an active infection from yourself or a named player                   |
| `/infection-status` | Lists all infected, immune, or permanently-damaged entities within 50 blocks |

`/infection-status` output format:

```

Cow: INFECTED (crimson_fever) 342s remaining

Sheep: IMMUNE 4821s remaining

Player: perm hearts lost: 2

```

---

## Technical Notes

- **Platform:** Fabric Loader 0.19.2, Fabric API 0.119.4+1.21.4, Minecraft 1.21.4

- **Infection state** is stored per-entity using Fabric Data Attachments and persists to disk via a Codec

- **Server-to-client sync** uses a custom `InfectionSyncPayload` packet sent every second while infected, and on state changes

- Animal infection state is tracked in memory during a session; it persists across chunk reloads but is not currently synced to clients (no visual indicator on animals)