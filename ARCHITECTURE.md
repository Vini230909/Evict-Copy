# Evict Map Generator Architecture

The plugin is split by responsibility so gameplay changes do not require editing one large class.

## Lifecycle

`EvictMapPlugin` is the composition root. It wires events, starts systems for a new round and triggers the next map after a victory.

`EvictRules` applies the fixed PvP rule set.

`EvictRuntimeState` stores the active auto-generation setting and map seeds.

## Generation

`EvictTerrainGenerator` owns hex geometry, wall templates, neutral cores and generation orchestration.

`ResourceGenerator` owns ores, water and oil.

`StartLoadout` owns the one-time personal starting schematic and resources.

## Round Systems

`TeamManager` owns personal teams, leaders, claims, elimination, surrender and victory conditions.

`CaptureManager` owns the delayed core-capture lifecycle and both captured-hex cleanup passes.

`AttritionManager` owns capture and long-range attrition.

`ExtinctionManager` owns the timed late-game ring collapse and center-core final phase.

`InviteManager` owns requests and claimed-player invitations.

## Commands

Command classes live under `commands/`.

`ClientCommands` is the single registration entry point for player-facing commands.

`ConsoleCommands` contains dedicated-server console commands.

`EvictCommandCatalog` defines command categories used by the filtered help menu.

`HelpCommands`, `EvictCommands` and `RoundEndCommands` contain focused player command implementations.
