# CLAUDE.md — Extinction

Server-side Mindustry plugin (game v157.4, Java 17 source) for persistent PvP on a
procedurally generated hex map. Runs on a dedicated server; clients install nothing.
Extinction started as a copy of the retired Evict plugin, so the code still says
"Evict" in many places; the project's name is Extinction.

Current version **1.13.2** — `plugin.json` `version` and the startup revision string
in `EvictMapPlugin` must always match.

One jar, two roles:
- **Hub** — the normal FFA server players connect to.
- **Worker** — an on-demand match server launched with `-Devict.duelWorker=true`;
  runs the normal game plus the `DuelWorker` referee for one `/play` match and
  self-terminates (`System.exit`) when empty.

## Read first, every session

1. `docs/CODE_RULES.md` — how the code must be shaped. `./check.sh` enforces it;
   RED means the change is not finished. The rules, the script and its baseline
   are edited only when the owner asks.
2. The section of `docs/GAMEPLAY.md` that the task touches — the canonical spec
   of how the game behaves. Keep it in sync when behaviour changes.
3. Only then the code files the task touches. There is no code map: the file
   tree under `src/Extinction/` is the map.

## Build & deploy

```bash
./gradlew jar   # → build/libs/EvictMapGenerator.jar
./check.sh      # GREEN or RED
```

No system `java`/`gradle` on this machine — point `JAVA_HOME` at the VSCode Java
extension's bundled JDK 21 first (glob `~/.vscode/extensions/redhat.java-*/jre/*`).
Build after every code change; fix compile errors immediately.

Deploy is done by the owner: copy the jar into the server's `config/mods/` and
restart — nothing else. `duel-workers/` must **not** be deleted: every worker spawn
re-copies `config/mods` into its folder and `refreshWorkerJars()` updates stale
server jars on hub startup, so workers pick up a new plugin automatically. The
server console is not a shell: set the port with `config port <n>`, host with
`host evict-map pvp`; `oregen auto` defaults ON, so hosting auto-generates a round.

## Working rules

- Preserve existing gameplay unless a gameplay change is explicitly requested.
- Restructuring is move-only: same commands, same properties file, same output.
  "Improve while moving" is two changes.
- The hub is the single DB writer. Workers report results via files and never
  touch SQLite.
- Before declaring a version finished: clean build, check GREEN, startup revision
  matches `plugin.json`, every changed and newly required file included.

## Other docs

- `docs/CHATLOG_SETUP.md` — operator walkthrough for the Discord chat mirror.
