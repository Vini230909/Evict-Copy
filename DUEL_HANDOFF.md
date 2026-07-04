# Evict 1v1 Duel — Handoff / Context Doc

Self-contained context so a fresh Claude session (or teammate) can continue the
1v1 duel work without the prior chat. Paste this whole file in. For deeper
detail see `AGENTS.md` (project spec) and `DUEL_ROADMAP.md` (feature planning).

---

## 1. What this project is

- **Server-side Mindustry plugin** (`EvictMapGenerator`, package
  `vini.evictmap`, main class `EvictMapPlugin`) for "Evict"-style persistent PvP
  on a procedurally generated hex map. Clients do **not** install it.
- Mindustry **v157.4**, Java source level **17** (machine has JDK 25).
- It is "vibecoded" with Codex too, hence `AGENTS.md` is the canonical spec —
  keep it in sync and prefer small, focused changes; do not touch normal Evict
  gameplay unless explicitly asked.
- The plugin runs on a dedicated server. We run **two roles** of the same jar:
  - **Hub** — the normal Evict FFA server players connect to (port `6567`).
  - **Worker** — a spawned 1v1 server, launched with the JVM flag
    `-Devict.duelWorker=true`, that hosts a single duel.

## 2. Build / run / deploy

- **Build (no gradle on PATH, no wrapper; use the bundled gradle):**
  ```
  ./.tools/gradle-9.2.0/bin/gradle jar
  ```
  Output: `build/libs/EvictMapGenerator.jar`.
- **Deploy:** copy that jar into the server's `config/mods/` and restart the
  server. Workers are provisioned **from the hub's own files**, so the hub's
  `config/mods/` (and `config/maps/`) must hold the current jar + the map.
- **Local server folders on the dev machine (Windows):**
  - Hub: `C:\Users\vini2\Desktop\MindustryServer\` — `server-release.jar`,
    `start-server.bat` (`java -jar server-release.jar`), `config/maps/evict-map.msav`,
    `config/mods/EvictMapGenerator.jar`, port `6567`.
  - The old manual worker folder `C:\Users\vini2\Desktop\Server duell\` is no
    longer needed — workers are now auto-spawned under `duel-workers/`.
- **After every plugin update, delete `duel-workers/`** so workers re-provision
  with the new jar (otherwise they run the stale jar).
- The server console is **not** a shell. Set the port with `config port <n>`
  (not `port`). Hosting: `host evict-map pvp`. `evictauto` defaults to ON, so a
  plain `host evict-map pvp` auto-generates an Evict round.

## 3. Git state

- Branch: **`feature/duel-1v1`**. `main` is at `223e222` (carries the perf
  optimizations only; the duel feature lives only on the branch). Nothing pushed.
- Key commits on the branch (newest first):
  - `c97f18a` scope all duel features to worker mode only (docs)
  - `6d4bf88` record duel design decisions (docs)
  - `ef1d253` add 1v1 duel roadmap (docs)
  - `506c381` **on-demand 1v1 worker pool** (DuelServerManager)
  - `aa1db9e` restore 1.2.28 AGENTS.md + document /play
  - `dc51a88` **/play 1v1 challenges** (DuelCommands)
  - `223e222` perf: full-assault + adjacency optimization (also on main)
- Commit messages end with a `Co-Authored-By: Claude ...` line. Only commit when
  asked; branch first if on main. `.claude/` (memory dir) stays untracked.

## 4. What is already IMPLEMENTED and working

### a) Performance optimizations (`223e222`, also on main)
- `TeamManager.isWithinOneHexOfOwnedCore` no longer runs a BFS per slot per unit
  every 5s; replaced with an O(1) adjacency check (`isSameOrAdjacentHex`) and the
  cheap test runs before the expensive owner lookup.
- `/fullassault` sweeps units once (not once per team) and snapshots cores once
  per pass (`TeamManager.snapshotSlotCores`) instead of re-reading the world per
  unit.

### b) `/play` duel challenges — `DuelCommands.java`
- `/play` (alias `/p`): menu of other online players → pick → that player gets an
  accept/decline menu → on accept, asks `DuelServerManager` to host a worker and
  redirect both players. Stale challenges cleared on player leave.

### c) On-demand worker pool — `DuelServerManager.java`
- One worker process per match; spawned on demand, torn down when empty, so idle
  duels cost no CPU. Per accept:
  1. reserve a free port in `basePort .. basePort+maxWorkers-1`,
  2. provision `duel-workers/duel-<port>/` from the hub's jar + `config/mods` +
     `config/maps` (skipped if present),
  3. launch `java -Devict.duelWorker=true -jar server-release.jar`,
  4. inject `config port <n>` and `host <map> pvp` on the worker's stdin,
  5. poll `127.0.0.1:<port>` until reachable (background thread → no server
     freeze),
  6. `Call.connect` both players to `duelServerIp:<port>`.
- Slot freed on worker process exit; backstops: startup grace, 30-min max
  lifetime, JVM shutdown hook kills all children.
- A worker (`-Devict.duelWorker=true`) currently runs **normal Evict** and
  self-terminates (`System.exit`) once empty (see `EvictMapPlugin`:
  `shutDownDuelWorkerIfEmpty`, startup/empty grace timers).

### d) Console config — `ConsoleCommands.java`
- `evictduelserver [ip] [basePort] [maxWorkers] [map]` — set/show the pool.
  Defaults: ip unset, basePort `6568`, maxWorkers `4` (max 10), map `evict-map`,
  jar `server-release.jar`. Shown in `evictstatus`. Persists in
  `config/evict-map-generator.properties` (keys `duel.server.ip`,
  `duel.server.port`, `duel.maxWorkers`, `duel.worker.map`, `duel.worker.jar`).
- Local test config: `evictduelserver 127.0.0.1`. **Public IP only works with
  port forwarding for the whole port range; home IPs are dynamic.**

### e) Milestone 1 referee — IMPLEMENTED, UNTESTED (`DuelWorker.java`, commit `2c95411`)
- Hub writes `duel.properties` (both player UUIDs + `hub.ip`/`hub.port`) into the
  worker folder at spawn; reads/logs `result.properties` on worker exit.
- Worker `DuelWorker`: reads the handshake, runs a 5s on-screen countdown
  (`Call.infoPopup`) once both players are connected, and on an Evict victory
  writes the result, announces the winner, and `Call.connect`s both players back
  to the hub; the worker then empties and self-terminates.
- Victory routed to the referee on a worker (`EvictMapPlugin.handleVictory`).
- On a worker `/die` has no leader/10-min gate; `/over` is disabled.

### Known limitations / untested
- The countdown is **cosmetic** — no engine freeze yet, so players can act during
  it. Real freeze is a fast-follow once pause behaviour is verified on a worker.
- A worker still hosts the **full Evict FFA map** (no small/mirrored 1v1 map yet);
  win = own all non-Fallen cores = eliminate the opponent.
- **Nothing in Milestone 1 has been run live yet** — see the test steps below.

## 5. Design decisions (locked)

- **Duel-only scope.** Everything below is worker-mode only
  (`-Devict.duelWorker=true`). **Normal Evict (the FFA hub) stays exactly as is.**
- **Always ranked.** No custom/unranked mode; map/mode never stored.
- **ELO is a placeholder.** Starts at **1000** and **does not change yet** —
  everyone stays 1000 until a real formula is chosen. Matches are still recorded
  so `/history` works; ELO before/after stay 1000 (delta 0) for now.
- **Win = own all cores** = Evict's existing victory check. `/die` sends a team's
  cores to Fallen; Fallen cores are placeholders nobody needs to own, so the
  survivor owns all real cores and wins. **Reuse Evict victory — no new win
  logic.**
- **No artificial time limit** (Evict Extinction ~1:40:00 is the cap).
- **Disconnect:** pause on leave, resume whenever the player returns (no
  time-based loss). Never-returner → cleaned up by the 30-min backstop.
- **Remove `/over` on duel workers only** (the FFA hub keeps `/over`).
- **Maps:** ranked maps should be diagonally mirrored for fairness — later.
- **Spectators (`/view`):** no cap; may chat normally for now.
- **`/history`:** match list only, screenshot style (win/loss vs opponent);
  `/info` already covers normal player stats.
- **Admin UUID** (`KShKEMpin0wAAAAAanrlJQ==` always admin, incl. on workers):
  **on hold** until the user reports needing it. Note: a hardcoded UUID is
  spoofable; proper solution later (Mindustry admin system + usid).

## 6. Coordination architecture (planned, build first)

File-based, no live socket, hub stays the single DB writer:
1. **`duel.properties`** (hub → worker, written at spawn into the worker dir):
   `player1.uuid`, `player2.uuid`, `hub.ip`, `hub.port`. Worker reads it and
   becomes referee for those two; anyone else who joins = spectator.
2. **`result.properties`** (worker → hub, written at match end before exit):
   `winner.uuid`, `loser.uuid`, `reason`. The hub reads it in the existing
   `DuelServerManager` `process.onExit()` hook and records the match centrally.
3. **Hub registry** (in memory): `uuid → active worker port`. Powers auto-rejoin,
   `evictduellist`, and `/view`.

## 7. Planned features and phases

- **Phase 0 — Foundation:** `duel.properties` + `result.properties` + hub
  registry; worker reads its config.
- **Phase 1 — Referee core:** 2-player duel, `/die` always available on worker,
  `/over` off on worker, win via Evict victory → both players returned to the hub
  → worker exits.
- **Phase 2 — Start gate + disconnect:** match starts only when both players are
  present, with a **5-second on-screen countdown**; disconnect pauses the match,
  resume on return; auto-rejoin (hub bounces a reconnecting player straight back
  to their worker).
- **Phase 3 — ELO + `/history`:** new `duel_matches` table (id, ts, p1/p2 uuid,
  winner, elo before/after — frozen at 1000 for now); `/history` shows the
  requester's recent matches in the screenshot style (no map/mode).
- **Phase 4 — Spectators + console:** `/view` (`/v`) joins as derelict spectator
  (unlimited, may chat); `evictduellist` / `evictduelkill <port>` /
  `evictduelkillall`.

## 8. Milestone 1 — IMPLEMENTED (untested). How to test tomorrow

1. Build and deploy the new jar to the **hub**, then **restart the hub** and
   **delete `duel-workers/`** so workers re-provision with the new jar:
   ```
   ./.tools/gradle-9.2.0/bin/gradle jar
   ```
   copy `build/libs/EvictMapGenerator.jar` to the hub `config/mods/`.
2. Hub console: `evictduelserver 127.0.0.1` (or set ip/ports), check `evictstatus`.
3. Two clients on the hub → one runs `/play`, picks the other, accept.
4. Expected:
   - Hub log: `1v1: sent <A> and <B> to duel worker 127.0.0.1:6568`.
   - On the worker (see `duel-workers/duel-6568/worker.log`): handshake loaded,
     then once both are in, a **5-second countdown popup** and "GO".
   - Play it out (or one types `/die`). When a team owns all non-Fallen cores,
     both players are sent back to the hub after ~5s and the worker exits.
   - Hub log on worker exit: `1v1 result on port 6568: winner=... loser=...`.
5. Watch for: countdown not showing (Call.infoPopup), players not returning
   (Call.connect back / hub address), worker not exiting, or victory not firing.

Originally proposed scope (now built), worker-mode only:
- Foundation (`duel.properties` handshake; `result.properties` back to hub).
- **Start gate:** match starts only when both UUIDs are connected → 5s on-screen
  countdown → play.
- `/die` always available on the worker; `/over` disabled on the worker.
- **Win = Evict victory** → both players redirected back to the hub → worker
  writes `result.properties` and exits.
- NOT in this milestone: disconnect pause/auto-rejoin, `/history`+DB, `/view`,
  `evictduellist`, real mirrored 1v1 map (runs on the normal Evict map for now).

## 9. Still open (small, non-blocking)

- Abandoned match (player never returns): rely on the 30-min worker backstop, or
  add an explicit abandonment rule?
- `/history` display while ELO is frozen: show `1000 → 1000 (+0)`, or hide ELO
  until the formula lands?
- Real ELO formula (start value, K-factor, provisional/decay) — later.

## 10. Key source files

- `EvictMapPlugin.java` — composition root, event wiring, worker-mode self-exit.
- `DuelCommands.java` — `/play` `/p`, challenge menus.
- `DuelServerManager.java` — on-demand worker spawn/track/redirect/cleanup.
- `EvictSettings.java` — persistent settings incl. the `duel.*` keys.
- `ConsoleCommands.java` — `evictduelserver`, `evictstatus`, etc.
- `TeamManager.java` — teams, captures, **victory check** (reused for 1v1 win),
  attrition helpers.
- `RoundEndCommands.java` — current FFA `/die` and `/over` (worker variants will
  differ).
- `PlayerDataManager.java` — async SQLite; `players` table already has `elo`,
  `peak_elo` (default 1000), `ranked_wins/losses/matches_played`. No
  match-history table yet.
