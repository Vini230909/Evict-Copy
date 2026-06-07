# Evict Map Generator

A server-side Mindustry plugin for Evict-style persistent PvP on a procedurally generated hex map.

## Features

- Procedural hex-map generation with walls, passages, ores, water and oil
- Fallen team `#14` as the neutral owner of unclaimed cores
- Personal player teams with protected starting hexes, a start schematic and starting resources
- Core captures with a 5-second replacement delay, hex cleanup and captured Core Shards
- Elimination messages, claims and leader-managed `/invite` requests
- Capture and long-range unit attrition
- Core units can build and mine, but cannot damage buildings or units
- Team-scoped `/fullassault` mode for unattended combat units
- `/die` surrender and `/over` early round ending
- Timed Extinction late game with collapsing outer rings and a center-core final phase
- Async SQLite player data storage for profiles, playtime and FFA results
- Automatic random-seed round resets
- Persistent tuning for attrition, walls, ore generation and Extinction terrain streaming

## Player Commands

```text
/help
/help dev
/fullassault
/invite [number]
/die
/over
/time
```

## Development Commands

```text
/forceend
/extinction
/attritioncore [t1-3] [t4] [t5]
/attritionrange [percent]
/wall [full-wall] [small-wall] [open] [passage]
/corecap [additional-per-core]
/spawnunit [unit] [amount] [team]
/info [online-player] [team] [#number]
```

Extinction terrain streaming can be adjusted from the server console:

```text
evictextinctiontiles [amount]
```

Stored player data can be searched from the server console:

```text
evictplayerinfo [name/uuid]
```

Console player lookup searches the latest stored name first. Old stored names
and UUIDs are used only if no latest-name match exists. If multiple online
players match `/info`, the command shows numbered matches; use the shown
`#number` to pick one. Running `/info` without arguments opens a clickable
online-player selection menu.

Ore presets can be adjusted from the server console:

```text
evictcopper [scale] [threshold] [octaves] [falloff]
evictlead [scale] [threshold] [octaves] [falloff]
evictcoal [scale] [threshold] [octaves] [falloff]
evicttitanium [scale] [threshold] [octaves] [falloff]
evictthorium [scale] [threshold] [octaves] [falloff]
evictscrap [scale] [threshold] [octaves] [falloff]
```

## Installation

Build the plugin JAR and place it in the Mindustry server `config/mods` folder. Clients do not need to install the plugin.
