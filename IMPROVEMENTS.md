# IMPROVEMENTS.md — Code Quality & Feature Backlog

A **categorised** wishlist for the EvictMapGenerator codebase: readability, file
structure (split / combine / extract), duplication to remove, and features to add
or replace. This is a menu, not a plan — nothing here changes gameplay unless it
says so.

Kept in sync as things ship. **Shipped** items live in one section at the top so
the backlog below stays a list of what's *still* worth doing.

## How to read the ratings

Every open item starts with a rating **`[1]`–`[5]`** blending *how much it helps*
with *how important it is*:

| Rating | Meaning |
|:------:|---------|
| **`[5]`** | Big win. High impact, low-to-medium effort, or fixes something that slows every future change. Do first. |
| **`[4]`** | Strong win. Clear maintainability gain, worth scheduling. |
| **`[3]`** | Solid cleanup. Nice improvement, moderate payoff. |
| **`[2]`** | Minor polish or a "someday" feature. |
| **`[1]`** | Cosmetic / optional. Only if you're already in the file. |

> These are suggested numbers — override with your own priority.

---

## Shipped

### 1.4.0 — the big restructure (`Huge-restruction-idea`)

- **C0 message/colour layer** — `core/text/` (`Colors`, `Text` builder, `Msg`). Infra done; **adoption is partial** (~276 inline `[colour]` tags still in the code — see C0 below).
- **Command framework (F)** — `core/cmd/` (`Command`, typed `Arg`/`ArgType`, `Perm`, `CommandContext`, `Commands`). Every client/console command now declares itself as data.
- **C2 / C3 / logging utils** — `core/util/Players` (`byUuid`/`byName`), `core/util/PluginLog`, `core/util/Ticks`, `core/io/PropertiesFile`.
- **B1** — `ConsoleCommands.register()` (was ~319 lines) is now a flat declarative list.
- **B2 / F** — `EvictMapPlugin.init()` (was ~267 lines) split into `bootstrap()` / `configureWorkerReferee()` / `freeEliminatedDuelTeam()` + event wiring.
- **Restart command** — `RestartManager` (`evictrestart` / `cancel` / `now`) + `docs/RESTART_LOOP.md`.
- **G leaderboard** — `/leaderboard` (`/top`, `/lb`) ranked ELO ladder.
- **G metrics** — `metrics/MetricsReporter` throttled hub status line.
- **C1 (partial)** — `duel/ipc/DuelResult` extracted as a typed read/write value. Handshake + status files are still hand-rolled (see C1 below).
- **E2 CI** — `.github/workflows/build.yml` builds the jar on every push.
- **Package reorg** — `core/`, `gen/`, `round/`, `data/`, `metrics/` subpackages.

---

## The evidence (current state)

Snapshot: **61 Java files, ~18,100 lines**. The big files are still the recurring
theme behind Section A. `register()` and `init()` are no longer the longest methods
(B1/B2 shipped) — the offenders now live inside the god classes.

| File | Lines |
|------|------:|
| `round/TeamManager.java` | 1,943 |
| `duel/DuelWorker.java` | 1,495 |
| `gen/EvictTerrainGenerator.java` | 1,473 |
| `commands/DuelCommands.java` | 1,393 |
| `duel/DuelServerManager.java` | 1,342 |
| `data/PlayerDataManager.java` | 1,226 |
| `gen/EvictSettings.java` | 1,051 |
| `gen/ResourceGenerator.java` | 990 |
| `EvictMapPlugin.java` | 718 |
| `commands/ConsoleCommands.java` | 685 |

**Still no test directory anywhere in the project** (Section E).

---

## A. Split the god classes

The biggest maintainability tax. Split along the seams already documented in the
code so each piece reads on its own.

- **`[5]` Break up `TeamManager` (1,943 lines, was 2,120).** The hub of the round
  system: team assignment, hex slots, leaders, claims, capture records,
  eliminations, surrender, victory checks, Extinction terrain queue, duel-mode
  toggles. It touches almost every feature — shrinking it pays off on nearly every
  future change.
  - *In progress:* `HexSlot` (the hex data holder) and `HexGeometry` (pure
    offset-grid distance/adjacency/nearest-slot math) are now their own files in
    `round/` — behavior-preserving pure moves, ~180 lines out.
  - *Remaining seams:* a `TeamRegistry` (id↔player↔leader↔claim maps + reset),
    a `SurrenderService` (`surrenderTeam`/asset-wipe/claimant decision), a
    `VictoryEvaluator` (victory/`/over`/elimination counting), and start-hex
    selection. These carry real logic — **do E1 (tests) first**, then extract.
- **`[4]` Split `DuelWorker` (1,495 lines).** Referee mixes handshake parsing, the
  start-gate/countdown state machine, disconnect-pause handling, and result/status
  file writing. Pull out the **IPC** (finish C1) and the **match state machine**.
- **`[4]` Split `DuelServerManager` (1,342 lines).** Port reservation, worker-folder
  provisioning, process spawn + stdin injection, readiness polling and slot
  bookkeeping are distinct. A `WorkerProvisioner` (filesystem) + `WorkerProcess`
  (spawn/stdin/lifetime) + the pool manager would read far more clearly.
- **`[4]` Split `commands/DuelCommands` (1,393 lines).** The `/play` flow is a big
  hand-rolled state machine (draft → pick → challenge → invites → launch). Extract
  a `MatchDraft` builder and per-menu handler classes.
- **`[4]` Split `PlayerDataManager` (1,226 lines).** Separate **schema/migrations**,
  a **DAO** (queries), and the **async writer** so each is readable and testable.
- **`[3]` Split `EvictTerrainGenerator` (1,473 lines).** Hex geometry, wall/passage
  carving, neutral-core placement and client world-sync are separable passes.
  Lower priority — self-contained subsystem you rarely touch.
- **`[3]` Split `ResourceGenerator` (990 lines).** Ore noise, water, oil and the
  world-summary/correction pass are distinct stages.
- **`[3]` Slim `EvictSettings` (1,051 lines).** See C4 — most of the bulk is the
  repetitive get/parse/set boilerplate a small key abstraction would collapse.

## B. Break up the monster methods

- **`[3]` `TeamManager.surrenderTeam()` (~100 lines)** and its asset-wipe helper.
  Extract the "who inherits the surrendered cores" decision and the wipe.
- **`[3]` `DuelCommands.handlePickSelection()` (~100 lines).** Split per menu-branch.
- **`[3]` `ExtinctionManager.update()` (~100 lines).** Drive the ring-collapse /
  center-core / overtime phases from a small phase enum, one `stepX()` each.
- **`[3]` `DuelServerManager.logResult()` / `EvictSettings.save()` (~90–117 lines).**
  Long linear methods; `save()` collapses via C4, `logResult()` splits per mode.

## C. Remove duplication / extract shared helpers

- **`[4]` Finish C0 adoption.** The `core/text/` layer exists but **~276 inline
  `[colour]` tags** remain across the code. Migrate them to `Colors`/`Text`/`Msg`
  so colours are consistent, un-typo'able, tweakable in one place, and translation
  becomes possible. Do it opportunistically per file you touch.
- **`[4]` Finish the worker-IPC module (C1).** `DuelResult` is a typed value now;
  `duel.properties` (handshake) and `status.properties` are still read/written by
  hand in both `DuelWorker` and `DuelServerManager`. Add `DuelHandshake` and
  `WorkerStatus` types under `duel/ipc/` with `read()`/`write()` to kill the drift
  risk between the two sides that must agree on the format.
- **`[3]` Settings-key abstraction (C4).** Each setting is a field + a manual
  load-parse + a manual `setProperty`. A typed `Setting<T>` (key, default, parse,
  format, validate) declared once per setting replaces the ~24×3 boilerplate in
  `EvictSettings` and makes adding a setting a one-liner. Biggest single win for
  that file, and it de-bulks `save()`/`load()` at the same time.
- **`[2]` Boolean parsing helper.** The `on/off/true/yes/no` parse in `evictauto`
  (and any future toggle) is hand-written; hoist a shared `parseOnOff(String)`.
- **`[2]` SQL schema extraction.** Move the `CREATE TABLE` DDL out of
  `PlayerDataManager` into constants or a `schema.sql` resource. (Queries already
  use PreparedStatements — good, leave those.)

## D. Readability & style polish

- **`[3]` Prune changelog-style javadoc.** Some class headers still narrate history
  ("Phase 1 … Implemented in the current phase …"). Git tells that story; replace
  with a crisp "what this class is responsible for *now*".
- **`[3]` Flatten deep nesting with guard clauses.** Deepest indentation still
  reaches ~40 spaces in `EvictTerrainGenerator`/`EvictMapPlugin`. Early-return
  guards over nested `if` pyramids help a lot.
- **`[3]` Command-name constants.** Command names appear as string literals in
  messages many times (`/play`, `/die`, `/invite`, …). Centralise them so renaming
  a command can't silently stale its help text and hints.
- **`[2]` Rename `GameplayManagerInterface`.** The `…Interface` suffix is a Java
  smell; name it for its role. Small, but it's a public seam.
- **`[2]` Prune dead private methods.** e.g. `DuelServerManager.splitUuidList` is
  unused (compiler warning). Sweep the warnings.

## E. Testing & build safety net

Every refactor in A/B is done blind. A thin safety net de-risks all of it.

- **`[4]` Unit-test the pure logic.** `EloCalculator` is already pure (great first
  target). As you split classes, extract and test the other pure bits: start-hex
  distance selection, victory/`/over` threshold math, water fractional-tries,
  attrition percentages, team-colour distinctness. High value precisely *because*
  it de-risks Section A/B. (CI already runs, so a `test` task would gate every push.)
- **`[2]` Assert the version-sync invariant.** A tiny build/test check that fails
  when `plugin.json` `version` and the `EvictMapPlugin` startup revision diverge —
  CLAUDE.md requires they match; enforce it automatically instead of by discipline.

## F. Architecture / design

- **`[3]` A player-lookup / roster service.** `Players.byUuid` exists; a couple of
  classes still reach into `Groups.player` with inline predicates. Route them all
  through the helper — one seam to stub in tests.
- **`[2]` Keep leaning on the mode-strategy pattern.** `MatchMode` (wire id) +
  `DuelMode` strategy + `duel/modes/` is a **good** design — the model to imitate
  when pulling conditionals out of `DuelWorker`/`TeamManager`. Noted so it's
  preserved, not refactored away.

## G. Features to add or replace

Ordered by usefulness for a persistent PvP server. (Leaderboard and metrics have
shipped — see the top.) These are lower priority than Sections A–E: do the
structural work first.

- **`[3]` Match-result webhook (Discord).** POST ranked/duel results to a webhook
  URL from the hub's existing `result.properties` parse, on a background thread, IP
  configured via a new `duel.webhook.url` setting. Great for a community server.
  Guard for servers with no outbound network; fail quietly.
- **`[3]` Admin / moderation toolkit.** Kick/ban/mute plus a rate-limiter on
  `/play` and `/invite` spam. Useful the moment the server is public. Build on the
  command framework + `Perm`.
- **`[3]` Graceful worker draining + auto-drain on restart.** A console toggle that
  makes `/play` refuse new matches so running workers empty out before a redeploy,
  auto-enabled when `evictrestart` queues. Complements the "delete `duel-workers/`
  after every update" rule.
- **`[2]` Config hot-reload.** Re-read `evict-map-generator.properties` from a
  console command without a restart, for tuning generation live.
- **`[2]` Seed sharing / preview.** Let players query the current seed (`/seed`) so a
  good map can be reproduced; optionally a short pre-round vote among candidate seeds.
- **`[2]` Match-history export.** Console command to dump `/history` rows to
  CSV/JSON for backups or external analysis.
- **`[2]` `/rematch`.** After a duel returns players to the hub, a one-tap
  re-challenge of the same opponent/mode. Reuses the whole `/play` launch path.
- **`[2]` Win-streak / recent-form tracking.** Surface a current ranked streak in
  `/info` and the leaderboard from data already stored.
- **`[2]` Console-tunable Extinction timing.** The 1:20 / 1:25 / 1:29 / 1:30
  milestones are hard-coded; an `evictextinction` setting (persisted like the rest)
  lets an operator tune round length without a rebuild.
- **`[2]` Reconnect UX polish.** Clearer messaging when a duel pauses for a
  disconnect and the rejoin window is counting down.
- **`[1]` Colour-blind-friendly team palette option.** A toggle that biases
  `TeamColors` toward a CVD-safe set.

---

## Suggested order of attack

1. **Finish C0 (colour adoption)** and **C1 (handshake/status IPC types)** — remove
   the remaining duplication *before* splitting, so the split classes share it.
2. **E1 (tests for pure logic)** — build the safety net on the now-running CI.
3. **A1 (`TeamManager`)** then the other Section A splits, backed by tests.
4. **C4 (`Setting<T>`)** to collapse `EvictSettings` (unblocks the A "slim settings"
   item and every future setting).
5. Features from Section G as desired — worker draining and the webhook are the
   cheapest high-value ones.
