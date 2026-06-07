# AGENTS.md

## Project

This repository contains a server-side Mindustry plugin for Evict-style persistent PvP on a procedurally generated hex map.

The plugin is intended for a dedicated Mindustry server. Clients do not install the plugin.

Current stable baseline: `1.2.14`.

## Workflow Rules

- Preserve existing gameplay unless the user explicitly asks for a gameplay change.
- Prefer small, focused changes over large rewrites.
- Keep command implementations out of `EvictMapPlugin`.
- Do not reintroduce god classes.
- After every code change, run:

```bash
gradle jar
```

- Fix compile errors before preparing replacement files.
- When providing replacement files, include every changed file and any newly required dependency file.
- If a later change depends on an earlier changed file, prepare a cumulative replacement ZIP so older repositories do not miss required methods.
- Keep `plugin.json` version and startup revision in `EvictMapPlugin.java` synchronized.

## Architecture

### Lifecycle

`EvictMapPlugin.java`
- Plugin entry point
- Event wiring
- Manager construction
- Round startup
- Automatic next-round reset

`EvictRules.java`
- Fixed Mindustry PvP rules

`EvictRuntimeState.java`
- Auto-generation state
- Current and next map seed

### Generation

`EvictTerrainGenerator.java`
- Hex geometry
- Filled hexes
- Walls and passages
- Neutral Fallen cores
- Final-seven protection for Extinction

`ResourceGenerator.java`
- Ores
- Water patches
- Oil patches
- Resource fallbacks

`StartLoadout.java`
- Starting schematic
- Starting resources

### Round Systems

`TeamManager.java`
- Personal teams
- Fallen team handling
- Team leaders
- Claims
- Eliminations
- Surrender
- Victory checks
- Extinction terrain queue

`CaptureManager.java`
- Destroyed-core handling
- Immediate captured-hex cleanup
- 5-second replacement delay
- Second anti-abuse cleanup
- Replacement Core Shard

`AttritionManager.java`
- Capture attrition
- Range attrition

`PlayerDataManager.java`
- Async SQLite player data writes
- Player profile rows
- FFA played/won counters
- Total and FFA playtime counters
- Reserved ranked/ELO columns

`CoreUnitDamageManager.java`
- Disables Alpha/Beta/Gamma core-unit combat damage
- Leaves core-unit building and mining intact

`InviteManager.java`
- Join requests
- Claimed players
- Leader-managed invites

`ExtinctionManager.java`
- Timed late-game Extinction event
- Sequential ring collapse
- Final center-core phase
- Overtime

### Commands

`EvictClientCommands.java`
- Single registration point for player chat commands

`EvictCommands.java`
- `/fullassault`
- `/info`
- Admin dev commands

`RoundEndCommands.java`
- `/die`
- `/over`

`RoundTimeCommands.java`
- `/time`
- Round runtime and player first-join runtime

`EvictHelpCommands.java`
- Filtered `/help`
- Separate `/help dev`

`EvictCommandCatalog.java`
- Dev-command list used by filtered help

`EvictConsoleCommands.java`
- Dedicated-server console commands
- Stored player-data lookup

## Important Gameplay Rules

### Teams

- Fallen team is always team `#14`.
- New players receive a personal team and one protected starting hex.
- Eliminated players become Fallen.
- Fallen players can still use chat and move their camera.
- Team leaders are the players who originally created their teams.

### Captures

- When a core dies, ownership changes logically immediately.
- Buildings inside the captured hex are deleted immediately.
- Replacement Core Shard appears after `5 seconds`.
- Buildings created during those `5 seconds` are deleted again immediately before replacement.
- Captured cores become Core Shards.
- `/die` surrender restores Fallen Nucleus cores in surrendered hexes.

### Attrition

Capture attrition:
- Applies once when a core is captured.
- Applies to normal units inside the `40-tile` capture radius.
- Tier-based values.
- Command:

```text
/attritioncore [t1-3] [t4] [t5]
```

Range attrition:
- Applies every `5 seconds`.
- Applies when units are at least `2` hexes away from an owned core.
- Same percentage for all eligible unit tiers.
- Default: `20%`.
- Command:

```text
/attritionrange [percent]
```

Core-spawned player units do not receive attrition.
Core units can build and mine, but do not deal combat damage to buildings or units.

Both values persist across full server restarts.

### Player Data

Persistent player data is stored in:

```text
config/evict-players.db
```

- Database writes run asynchronously on one background writer thread.
- Profiles are keyed by player UUID.
- Stored values include last name, first seen, last seen, total playtime, FFA playtime, FFA played and FFA won.
- All observed names are stored per UUID in `player_names`.
- Ranked playtime, ranked wins, ranked losses, ranked matches played, ELO and peak ELO columns exist for later ranked/1v1 features.
- FFA playtime is counted only after a player receives a personal team in that round.
- No IP addresses are stored.
- `/info` is admin-only and opens a clickable online-player selection menu with two players per row and a bottom cancel button.
- `/info [name] [team] [#number]` is admin-only and searches online players by partial name. The optional team ID filters duplicate online names; `#number` selects one result from the duplicate list.
- Console command `evictplayerinfo [name/uuid]` searches stored database rows by partial latest name first. Old names and UUIDs are searched only if no latest-name match exists.

### Full Assault

```text
/fullassault
```

- Team-scoped toggle, never global.
- Updates every `5 seconds`.
- Sends eligible unattended combat units to the nearest enemy core.
- Ignore player-controlled units.
- Ignore units using mine, assist, rebuild or repair commands.

### Invites and Claims

```text
/invite
/invite [number]
```

- Only the team leader can accept players.
- Fallen players can request teams.
- When a team loses its final core, the team that destroyed the most cores claims it.
- On a tie, the team that destroyed the final core wins the claim.
- Claimed players can join only their claimant's team.
- Fallen itself cannot be claimed.

### Surrender

```text
/die
```

- Leader only.
- No confirmation required.
- Immediately destroys all team buildings and units.
- Converts all surrendered hexes to Fallen.
- Restores Fallen Nucleus cores.
- Releases claims held by the surrendered team.

### Early Round End

```text
/over
```

- Available to any player in a personal team.
- No confirmation required.
- Requires at least `50%` of all cores.
- Teams that never owned more than one core do not block `/over`.
- Teams that expanded beyond one core must be fully eliminated.
- Disabled once the 10-minute Extinction warning begins.

### Time

```text
/time
```

- Available to every player.
- Shows how long the current round has been running.
- Shows how long the player has been connected since their first join this round.

### Extinction

Normal timeline:
- At `01:20:00`: global 10-minute warning and `/over` disabled
- At `01:25:00`: global 5-minute warning
- At `01:29:00`: global 1-minute warning
- At `01:30:00`: Extinction begins
- Outer rings collapse from farthest to nearest
- Next ring starts after the prior ring is fully processed and another `90 seconds` pass
- Within a ring, core/hex collapse has no artificial delay
- Terrain-to-Space conversion is throttled separately
- If all surviving cores belong to one team during Extinction, that team wins immediately

Final phase:
- The center hex and its six neighboring hexes are protected from procedural filling
- When only those `7` hexes remain, a `4-minute` center-core phase begins
- The team owning the middle core after `4 minutes` wins
- If the middle core is still Fallen, overtime continues until a personal team captures it

Admin test command:

```text
/extinction
```

Terrain streaming console command:

```text
evictextinctiontiles [amount]
```

- Shows or persists Space-floor conversions per tick
- Default: `120`
- Allowed range: `1..4096`
- Applies immediately
- Persists to `config/evict-map-generator.properties`

## Help Menu Rules

Normal help:

```text
/help
/help 2
```

- Must show only normal player commands
- Must not show `/help`
- Must not advertise `/help dev`

Dev help:

```text
/help dev
/help dev 2
```

- Visible to all players
- Contains dev commands
- Dev commands themselves remain admin-only where applicable

Current dev commands:
- `/forceend`
- `/extinction`
- `/attritioncore`
- `/attritionrange`
- `/wall`
- `/corecap`
- `/spawnunit`
- `/info`

## Persistent Console Settings

Ore settings:

```text
evictcopper [scale] [threshold] [octaves] [falloff]
evictlead [scale] [threshold] [octaves] [falloff]
evictcoal [scale] [threshold] [octaves] [falloff]
evicttitanium [scale] [threshold] [octaves] [falloff]
evictthorium [scale] [threshold] [octaves] [falloff]
evictscrap [scale] [threshold] [octaves] [falloff]
```

Wall settings:
- `/wall [full-wall] [small-wall] [open] [passage]`

Persistent settings are stored in:

```text
config/evict-map-generator.properties
```

## Filled Hex Generation

- Minimum filled hexes: `6`
- Maximum filled hexes: `12`
- Center bonus: up to `12%`
- Final seven Extinction hexes must never be filled

Current chance structure:
- Border hexes: `11%`
- Second ring: `3.5%`
- Inner hexes: `1%`
- Chain start: `22%`
- Chain continue: `48%`
- Maximum chain length: `3`

## Water Generation

- No guaranteed water per core hex
- No per-core water fallback
- Water patches are random across the map
- Patch sizes: `1..9`
- Larger water patches are progressively rarer

## Safety Notes

- Avoid large one-tick network bursts.
- Terrain floor changes must remain throttled.
- Extinction should remove logical ownership, cores, buildings and units immediately, while visual terrain conversion may stream gradually.
- Do not send thousands of `setFloorNet` packets in one tick.
- Preserve the double-wipe capture protection.


## Build Output

After a successful build, the plugin JAR is produced by:

```bash
gradle jar
```

Before declaring a version finished:
1. Confirm compilation succeeds.
2. Check that the startup revision matches `plugin.json`.
3. Verify all changed and newly required files are included.
4. Prefer a cumulative replacement ZIP when prior file state is uncertain.
