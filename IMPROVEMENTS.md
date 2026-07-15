# IMPROVEMENTS.md — Code Quality & Feature Backlog

A **categorised** wishlist of things that could be made better in the EvictMapGenerator
codebase: readability, file structure (split / combine / extract), duplication to
remove, and useful features to add or replace existing ones with.

This is a menu, not a plan. Nothing here changes gameplay unless it says so.

## Status — shipped in the 1.4.0 restructure

Done on the `Huge-restruction-idea` branch (build stays green):

- **C0 message/colour layer** — `core/text/` (`Colors`, `Text` builder, `Msg`).
- **Command framework (F)** — `core/cmd/` (`Command`, `Arg`/`ArgType` typed `"name:type"` args, `Perm`, `CommandContext`, `Commands`).
- **C2/C3 + logging utils** — `core/util/Players`, `core/util/PluginLog`, `core/util/Ticks`, `core/io/PropertiesFile`.
- **B1** — `ConsoleCommands.register()` (was ~319 lines) is now a flat declarative list on the framework; `evictrestart` added.
- **B2** — `EvictMapPlugin.init()` broken into `bootstrap()` / `configureWorkerReferee()` / `freeEliminatedDuelTeam()` + event wiring.
- **Restart command** — `RestartManager` implemented (`evictrestart` / `cancel` / `now`) + external loop guide in `docs/RESTART_LOOP.md`.
- **Features (G)** — `/leaderboard` (`/top`,`/lb`) + `metrics/MetricsReporter`.
- **Package reorg** — `core/`, `gen/`, `round/`, `data/`, `metrics/` subpackages; cross-package API promoted to `public`.

Not yet done (candidates below still open): full **A** god-class splits (TeamManager/DuelWorker/DuelServerManager internals), **C1** duel-IPC value types, **C4** `Setting<T>`, **E** tests, broad **C0** adoption across the remaining ~250 inline colour tags.

## How to read the ratings

Every item starts with a rating **`[1]`–`[5]`** that blends *how much it helps* with
*how important it is* into one number:

| Rating | Meaning |
|:------:|---------|
| **`[5]`** | Big win. High impact, low-to-medium effort, or fixes something that actively slows every future change. Do these first. |
| **`[4]`** | Strong win. Clear readability/maintainability gain, worth scheduling. |
| **`[3]`** | Solid cleanup. Nice improvement, moderate payoff. |
| **`[2]`** | Minor polish or a "someday" feature. |
| **`[1]`** | Cosmetic / optional. Only if you're already in the file. |

> These are **my** suggested numbers — override them with your own priority.
> Add your own column/mark in front if you want to re-rank.

## The evidence (current state)

Snapshot of the source tree — **41 Java files, ~16,800 lines**. The big files and the
longest methods are the recurring theme behind most items below.

| File | Lines | Longest method |
|------|------:|----------------|
| `TeamManager.java` | 2,114 | `surrenderTeam()` ~102 |
| `duel/DuelWorker.java` | 1,515 | `loadHandshake()` ~81 |
| `EvictTerrainGenerator.java` | 1,469 | `syncGeneratedWorld()` ~57 |
| `commands/DuelCommands.java` | 1,390 | `handlePickSelection()` ~102 |
| `duel/DuelServerManager.java` | 1,326 | `logResult()` ~90 |
| `PlayerDataManager.java` | 1,191 | — |
| `EvictSettings.java` | 1,051 | `save()` ~117 |
| `ResourceGenerator.java` | 988 | `summarizeWorld()` ~59 |
| `commands/ConsoleCommands.java` | 923 | **`register()` ~319** |
| `EvictMapPlugin.java` | 669 | **`init()` ~267** |

**No test directory exists anywhere in the project.**

---

## A. Split the god classes

The biggest maintainability tax. Each of these files does several jobs; a new reader has
to hold the whole thing in their head. Splitting along the seams that already exist in the
code (they're even documented in comments) makes each piece readable on its own.

- **`[5]` Break up `TeamManager` (2,114 lines).** It's the single largest file and the
  hub of the round system: team assignment, hex slots, leaders, claims, captures record,
  eliminations, surrender, victory checks, Extinction terrain queue, duel-mode toggles.
  Natural extractions: `HexSlot` + hex geometry/start-selection, a `TeamRegistry`
  (id↔player↔leader maps), a `SurrenderService`, and a `VictoryEvaluator`. This one file
  touches almost every feature — shrinking it pays off on nearly every future change.
- **`[4]` Split `DuelWorker` (1,515 lines).** Referee mixes handshake parsing, the
  start-gate/countdown state machine, disconnect-pause handling, result-file writing, and
  the periodic status file. Pull out the **handshake/result/status file IPC** (see item C1)
  and the **match state machine** as separate collaborators.
- **`[4]` Split `DuelServerManager` (1,326 lines).** Port reservation, worker-folder
  provisioning, process spawning, stdin injection, readiness polling, and slot bookkeeping
  are distinct responsibilities. A `WorkerProvisioner` (filesystem) + `WorkerProcess`
  (spawn/stdin/lifetime) + the pool manager would read much more clearly.
- **`[4]` Split `commands/DuelCommands` (1,390 lines).** The `/play` menu flow is a big
  hand-rolled state machine (draft → pick menu → challenge → invites → launch). Extract a
  `MatchDraft` builder and per-menu handler classes; today the selection handlers
  (`handleSelection`, `handlePickSelection` ~102 lines, `handleChallengeResponse`, …) all
  live together.
- **`[4]` Split `PlayerDataManager` (1,191 lines).** It bundles: the background writer
  thread, the SQLite schema/DDL, the query methods, ELO bookkeeping, and playtime
  accounting. Separate **schema/migrations**, a **DAO** (queries), and the **async writer**
  so each is independently readable and testable.
- **`[3]` Split `EvictTerrainGenerator` (1,469 lines).** Cohesive but huge. Hex geometry,
  wall/passage carving, neutral-core placement, and the client world-sync are separable
  passes. Lower priority because it's a fairly self-contained subsystem you rarely touch.
- **`[3]` Split `ResourceGenerator` (988 lines).** Ore noise, water patches, oil, and the
  world-summary/correction pass are distinct stages that could each be a small class.
- **`[3]` Slim `EvictSettings` (1,051 lines).** See C4 — most of the bulk is repetitive
  get/set boilerplate that a small key abstraction would collapse.

## B. Break up the monster methods

Independent of file size, these single methods are too long to read top-to-bottom.

- **`[5]` `ConsoleCommands.register()` — ~319 lines.** Every `evict*` console command is an
  inline lambda in one method. Give each command its own private method (or its own tiny
  class), like `registerGenCommand(handler)`, `registerWaterCommand(handler)` (that split
  is already started for water — finish it everywhere).
- **`[5]` `EvictMapPlugin.init()` — ~267 lines.** The composition root wires every event
  listener inline with multi-line lambdas (WorldLoad, Play, elimination handler, spectator
  demotion, …). Extract each listener into a named method or a small `*Listeners` class so
  `init()` becomes a readable table of contents.
- **`[4]` `EvictSettings.save()` / load — ~117 lines.** 24 hand-written `setProperty`
  calls plus matching parse-on-load. Collapse via C4.
- **`[3]` `TeamManager.surrenderTeam()` — ~102 lines** and **`clearSurrenderedTeamAssets()`
  ~68.** Extract the "who inherits the surrendered cores" decision and the asset-wipe into
  named helpers.
- **`[3]` `DuelCommands.handlePickSelection()` — ~102 lines.** Split per menu-branch.
- **`[3]` `ExtinctionManager.update()` — ~101 lines.** The ring-collapse/center-core/overtime
  phases could each be a `stepX()` method driven by a small phase enum.

## C. Remove duplication / extract shared helpers

Repeated patterns that should live in one place.

- **`[5]` Message & colour constants (C0).** There are **~275 inline colour tags**
  (`[scarlet]`, `[accent]`, `[white]`, …) scattered through the code, and **127 message
  sends**. Introduce a `Msg`/`Colors` helper (or a small message catalogue). Wins:
  consistent colours, no typo'd tags, one place to tweak wording, and it's the prerequisite
  for any future translation/i18n.
- **`[4]` Shared worker-IPC module (C1).** `duel.properties` (handshake),
  `result.properties`, and `status.properties` are read/written by hand in both
  `DuelWorker` and `DuelServerManager` (Properties handling appears across **7 files**).
  One `DuelHandshake` / `DuelResult` / `WorkerStatus` type per file with `read()`/`write()`
  removes the drift risk between the two sides that must agree on the format.
- **`[3]` Player-by-UUID lookup helper (C2).** The
  `Groups.player.find(p -> p.uuid().equals(uuid))` idiom recurs ~9–12 times. A single
  `Players.byUuid(uuid)` (returning `Player`/`Optional`) removes the repeated lambda.
- **`[3]` Settings-key abstraction (C4).** Each setting is a field + a manual load-parse +
  a manual `setProperty`. A tiny typed `Setting<T>` (key, default, parse, format) declared
  once per setting would replace the 24×3 boilerplate in `EvictSettings` and make adding a
  setting a one-liner.
- **`[3]` Properties file I/O helper (C3).** Loading/saving a `Properties` file with the
  same mkdirs + error-log dance is repeated. One `PropertiesFile` utility (load/store with
  logging) removes it. Pairs naturally with C1 and C4.
- **`[2]` Boolean parsing helper.** The `on/off/true/yes/no` parsing (e.g. `evictauto`) is
  hand-written; a `parseOnOff(String)` helper is a small readability win where it's used.
- **`[2]` SQL schema extraction.** The `CREATE TABLE` DDL is a long string-concatenation
  block inside `PlayerDataManager`. Move schema to constants or a `schema.sql` resource so
  the table shape is readable at a glance. (Queries already use PreparedStatements — good,
  no injection risk, leave those.)

## D. Readability & style polish

Smaller, in-place changes that make files nicer to read.

- **`[3]` Prune changelog-style javadoc.** Several classes carry historical narration in
  their doc comments — e.g. `TeamManager`'s header lists "Phase 1 … Implemented in the
  current phase … Implemented in the current phase …". Git already tells that story;
  replace with a crisp "what this class is responsible for *now*" summary.
- **`[3]` Flatten deep nesting with guard clauses.** Deepest indentation reaches ~44 spaces
  in `EvictTerrainGenerator` and `EvictMapPlugin`, ~36 in `TeamManager`/`PlayerDataManager`/
  `EvictSettings`/`DuelServerManager`/`ConsoleCommands`. Early-return guards over nested
  `if` pyramids reduce this a lot.
- **`[3]` Command-name constants.** Command names appear as string literals in messages
  ~149 times (`/play`, `/die`, `/invite`, …). If a command is renamed, help text and hints
  silently go stale. Centralising the names keeps messages and registration in sync.
- **`[2]` Wrap the handful of >120-char lines** (12 in `ConsoleCommands`, 8 in
  `CoreCapture`, a few elsewhere) for consistency with the rest of the file.
- **`[2]` Rename `GameplayManagerInterface`.** The `…Interface` suffix is a Java smell; name
  it for its role (e.g. `GameplayManager` as the interface, implementations named for what
  they do). Small, but it's a public seam.

## E. Testing & build safety net

There are **no tests at all**, so every refactor above is done blind. Adding even a thin
safety net makes the whole rest of this list safer and faster to execute.

- **`[4]` Unit-test the pure logic.** `EloCalculator` is already pure (great target).
  Extract and test the other pure bits as you split classes: start-hex distance selection,
  victory/`/over` threshold math, water fractional-tries, attrition percentages, team-color
  distinctness. High value precisely *because* it de-risks Section A/B.
- **`[3]` A `./gradlew jar` CI check.** A GitHub Action (there's already a `.github/`) that
  builds the jar on every push catches compile breaks before deploy — cheap insurance given
  the "build after every change" rule.
- **`[2]` Assert the version-sync invariant.** CLAUDE.md requires `plugin.json` `version`
  and the `EvictMapPlugin` startup revision string to match. A tiny build/test check that
  fails when they diverge enforces it automatically.

## F. Architecture / design

Structural moves that reduce coupling.

- **`[4]` Extract event wiring out of `init()`.** (Same target as B2, framed as design.) A
  dedicated `EventListeners` collaborator that receives the managers keeps the composition
  root to construction + wiring, not behaviour.
- **`[3]` A small player-lookup / roster service.** Several classes reach into
  `Groups.player` directly with duplicated predicates (C2). A thin service centralises it
  and gives you one seam to stub in tests.
- **`[2]` Command auto-registration.** Client/console commands are registered by hand in one
  method each. A tiny `Command` abstraction (name, args, help, handler) registered from a
  list would shrink `ClientCommands`/`ConsoleCommands` and make `/help` generation
  data-driven instead of hand-maintained. (Weigh against Mindustry's `CommandHandler` API —
  keep it thin.)
- **`[2]` Keep leaning on the mode-strategy pattern.** `MatchMode` (wire id) + `DuelMode`
  strategy + per-mode classes under `duel/modes/` is a **good** design — the model to
  imitate when you pull conditionals out of `DuelWorker`/`TeamManager`. Noted so it's
  preserved, not refactored away.

## G. Features to add or replace

The "special features" part of the ask. Ordered by usefulness for a persistent PvP server.

- **`[4]` `/leaderboard` / `/top` command.** ELO and match data already persist in
  `evict-players.db`; surface a top-N ranked ladder (and maybe FFA-wins) in-game. High
  player-facing value for data you already collect.
- **`[3]` Match-result webhook (Discord).** Post ranked/duel results to a webhook. Great for
  a community server; reuses the `result.properties` the hub already parses.
- **`[3]` Admin / moderation toolkit.** Kick/ban/mute, and a rate-limiter on `/play` and
  `/invite` spam. Useful the moment the server is public.
- **`[3]` Graceful worker draining.** A console command to stop assigning new matches to a
  worker and let it finish, for clean redeploys (complements the "delete `duel-workers/`
  after every update" rule).
- **`[2]` Config hot-reload.** Re-read `evict-map-generator.properties` from a console
  command without a restart, for tuning generation live.
- **`[2]` Seed sharing / preview.** Print or let players query the current seed so a good map
  can be reproduced; optionally a `/seed` command.
- **`[2]` Basic metrics/observability.** Periodic log line or a status file with player
  count, active matches, worker pool usage — helps diagnose the pool without reading logs.
- **`[2]` Match-history export.** A console command to dump `/history` rows to CSV/JSON for
  external analysis or backups.
- **`[2]` Reconnect UX polish.** Clearer messaging when a duel is paused for a disconnect and
  the rejoin window is counting down.

---

## Suggested order of attack

If you want a path rather than a menu, the highest leverage sequence is:

1. **C0 (message/colour constants)** and **C1 (worker-IPC module)** — remove duplication
   *before* splitting, so the split classes share the extracted helpers.
2. **B1 + B2** (`register()` and `init()`) — fast, dramatic readability wins with low risk.
3. **E1 (tests for pure logic)** — build the safety net.
4. **A1 (`TeamManager`)** then the other Section A splits, now backed by tests.
5. Features from Section G as desired.
