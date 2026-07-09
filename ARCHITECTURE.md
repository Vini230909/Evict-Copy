# Evict Map Generator Architecture

The plugin is split by responsibility so gameplay changes do not require editing one large class.

## Lifecycle

`EvictMapPlugin` is the composition root. It wires events, starts systems for a new round and triggers the next map after a victory. On a duel worker (`-Devict.duelWorker=true`) it additionally wires the match referee.

`gameplay/RulesApplier` applies the fixed PvP rule set, the banned blocks, the building-vs-building damage scaling and the core-unit damage removal.

`EvictRuntimeState` stores the active auto-generation setting and map seeds.

`EvictSettings` loads and persists the tuning file `config/evict-map-generator.properties`.

## Generation

`EvictTerrainGenerator` owns hex geometry, wall templates, neutral cores and generation orchestration.

`HexGrid` holds the shared hex-grid geometry constants (row/column counts, hex radius) that generation and the round systems must agree on.

`ResourceGenerator` owns ores, water and oil.

`StartLoadout` owns the one-time personal starting schematic and resources.

## Round Systems

`TeamManager` owns personal teams, leaders, claims, elimination, surrender and victory conditions.

`CoreCapture` owns the delayed core-capture lifecycle, verified core placement and both captured-hex cleanup passes.

`gameplay/AttritionManager` owns capture and long-range attrition.

`gameplay/AttackManager` owns the team-scoped /fullassault toggle.

`gameplay/ExtinctionManager` owns the timed late-game ring collapse and center-core final phase.

`InviteManager` owns requests and claimed-player invitations.

`TeamColors` picks new team ids whose colours stay distinguishable from those already in play.

## Persistence and Ranks

`PlayerDataManager` owns the async SQLite storage: profiles, playtime, FFA counters and the /history match rows.

`RankManager` owns tournament name tags and worker-synced admin recognition.

## Matches (duel workers)

`duel/DuelServerManager` is the hub-side worker pool: it reserves ports, provisions worker folders, spawns worker processes and redirects the rostered players.

`duel/DuelWorker` is the worker-side referee: handshake, start gate, countdown, disconnect pauses, result file and periodic status file.

`duel/MatchMode` and `duel/modes/` define the mode ids and per-mode rules (1v1, Teams, FFA, Training, Sandbox).

## Commands

Command classes live under `commands/`.

`ClientCommands` is the single registration entry point for player-facing commands.

`ConsoleCommands` contains dedicated-server console commands.

`HelpCommands`, `DuelCommands`, `HistoryCommands`, `InfoCommands`, `RoundEndCommands` and `RoundTimeCommands` contain focused player command implementations.
