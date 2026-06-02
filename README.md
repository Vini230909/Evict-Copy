# EvictMapGenerator

Server-side Mindustry plugin prototype for generating the Evict-style wall and connection layout directly on a loaded map.

## Current scope

Implemented:

- 6×6 staggered hex grid
- horizontal center offset: `75`
- diagonal center offset: `37 / 64`
- outer room-circle radius: `38`
- mirrored inner guaranteed-floor polygon
- floor: `Dark Sand`
- wall: `Dirt Wall`
- rounded outer edges toward completely filled hexes and toward the outside
- four connection variants:
  - full wall
  - one-tile thin wall
  - open
  - wall with centered 7-tile passage
- rare completely filled hexes, biased toward the map edge
- connected playable network: filled hexes may not split the map, and a random spanning tree forces a traversable route between all normal hexes

Not implemented yet:

- nucleus cores
- teams
- ores
- water
- PvP round logic

## Base map

Create a blank test map in the Mindustry editor. It needs to be at least:

```text
509 × 417 tiles
```

Using something slightly larger such as `520 × 430` is easier.

The generator overwrites terrain floors, terrain walls and overlays. Synthetic blocks such as a temporary editor core are preserved for now, so the map can still be hosted before core generation is added.

Do not test this on an important map without a backup.

## Install on a dedicated server

Build the JAR and copy it into:

```text
<server folder>/config/mods/
```

Restart the server, then run:

```text
mods
evictstatus
```

## Recommended test flow

Enable generation for the next hosted map:

```text
evictauto on
evictseed 12345
host <blank-map-name> sandbox
```

`evictauto on` is preferred because generation runs during map loading, before clients connect.

For quick testing on an already hosted map:

```text
evictgen 12345
```

If players are already connected during `evictgen`, reconnect afterwards if the terrain is not refreshed correctly.

## Commands

```text
evictstatus
evictauto <on/off>
evictseed [seed/random]
evictgen [seed/random]
```

## Build with GitHub Actions

Upload this folder to a GitHub repository. The included workflow builds the JAR automatically:

```text
Actions → Build plugin → latest run → Artifacts → EvictMapGenerator
```

Unzip the downloaded artifact and place the contained JAR into the server's `config/mods/` folder.

## Build locally

Install JDK 17 and Gradle, then run:

```text
gradle jar
```

The JAR appears in:

```text
build/libs/EvictMapGenerator.jar
```
