# Evict Map Generator

A server-side Mindustry plugin for Evict-style persistent PvP on a procedurally generated hex map.

## Features

- Procedural hex-map generation with walls, passages, ores, water and oil
- Fallen team `#18` as the neutral owner of unclaimed cores
- Personal player teams with protected starting hexes, a start schematic and starting resources
- Core captures with a 5-second replacement delay, hex cleanup and captured Core Shards
- Elimination messages, claims and leader-managed `/invite` requests
- Capture and long-range unit attrition
- Team-scoped `/fullassault` mode for unattended combat units
- `/die` surrender and `/over` early round ending
- Automatic random-seed round resets
- Persistent tuning for attrition, walls and ore generation

## Player Commands

```text
/help
/help dev
/fullassault
/invite [number]
/die
/over
```

## Development Commands

```text
/forceend
/attritioncore [t1-3] [t4] [t5]
/attritionrange [percent]
/wall [full-wall] [small-wall] [open] [passage]
/corecap [additional-per-core]
/spawnunit [unit] [amount] [team]
```

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
