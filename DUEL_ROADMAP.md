# Duel (1v1) Roadmap

Planning document. **Nothing here is implemented yet** unless it is also in
`AGENTS.md`. This captures the requested features, a design approach for each,
the shared groundwork they all need, a suggested order, open questions for the
team, and risks.

## Where we are today

- `/play` (+ `/p`): challenge menu -> accept/decline.
- `DuelServerManager`: on-demand worker pool. Spawns a worker process per match,
  provisions it from the hub files, hosts it, redirects both players, frees the
  slot when the worker exits.
- Workers currently host the **full Evict FFA** — there is no real 1v1 mode yet.
- DB (`players`) already has `elo`, `peak_elo` (default 1000), `ranked_wins`,
  `ranked_losses`, `ranked_matches_played`. There is **no match-history table**.

Almost everything below is worker-side "referee" behaviour plus hub<->worker
coordination, so the foundation matters more than any single feature.

## Decisions (resolved 2026-06-17)

These override the per-feature notes further below where they differ.

- **Always ranked.** Every duel is ranked; there is no custom/unranked mode, so
  map/mode never need to be stored and `duel.properties` needs no ranked flag.
- **ELO is a placeholder.** Start = 1000 and **does not change yet** — everyone
  stays at 1000 until a real rating formula is chosen. Matches are still
  recorded so history works; ELO before/after stay 1000 (delta 0) for now.
- **Win = own all cores** (Evict's existing victory). `/die` sends a team's
  cores to Fallen; Fallen cores are placeholders nobody needs to own, so the
  surviving team then owns all real cores and wins. The 1v1 worker reuses
  Evict's normal victory check — no separate win logic needed.
- **No artificial time limit.** Evict Extinction (~1:40:00) is the natural cap.
- **Disconnect:** pause on leave, resume whenever the player returns (no
  time-based loss — the opponent just loses time). A never-returning player is
  cleaned up by the worker max-lifetime backstop; no result recorded.
- **Remove `/over` completely** (does not fit "own all cores"). Scope to
  confirm: duel workers only, or the FFA hub too?
- **Maps:** ranked maps should be **diagonally mirrored** for fairness — later.
- **Spectators (`/view`):** no cap; spectators may chat normally for now.
- **`/history`:** match list only, screenshot style (win/loss vs opponent).
  `/info` already covers normal player stats. ELO columns shown once the real
  formula exists.
- **Admin UUID:** hardcoded test admin is fine for now (spoofable — proper
  solution later).

## Still open (small)

- `/over` removal scope: duel workers only, or remove the command everywhere?
- Abandoned match (player never returns): rely on the 30-min worker backstop, or
  add an explicit abandonment rule?
- `/history` while ELO is frozen: show `1000 -> 1000 (+0)`, or hide ELO until the
  formula lands?

## Foundation: hub <-> worker coordination (build first)

Today the hub and worker only share one signal: process exit. To do start
gates, pausing, surrender, auto-return, spectators and ELO we need a little more,
but we can stay file-based (no live socket) and keep the **hub as the single DB
writer**.

1. **Handshake file (hub -> worker), written into the worker dir at spawn:**
   `duel.properties` with
   - `player1.uuid`, `player2.uuid` (who the actual match players are)
   - `hub.ip`, `hub.port` (so the worker can send players back)
   - `ranked` (true/false)
   - later: spectators allowed, map, time limit
   The worker reads this in `-Devict.duelWorker=true` mode and becomes a referee
   for exactly those two players. Anyone else who joins is a spectator.

2. **Result file (worker -> hub), written at match end before the worker exits:**
   `result.properties` with `winner.uuid`, `loser.uuid`, `reason`
   (core / surrender / timeout / abort), `ranked`. The hub already has a
   `process.onExit()` hook in `DuelServerManager`; it reads this file there and
   does ELO + history centrally. No second DB writer, no locking fight.

3. **Hub match registry (in memory):** `uuid -> active worker port` and
   `port -> {player1, player2, state, startedAt}`. Powers auto-rejoin, the
   console list, and `/view`. Cleared when the worker exits.

Rationale: reuses the existing process-exit signal, avoids a live network
channel, and keeps all persistence in the hub's existing async SQLite writer.

## Features

### 1. Console: list and manage running instances

- `evictduellist`: one line per active worker — port, state, the two assigned
  player names/uuids, uptime, alive/PID. (Live in-match player count needs a
  worker status file; ship the hub-known info first.)
- `evictduelkill <port>`: force-stop one worker (destroys the process, frees the
  slot).
- `evictduelkillall`: stop all.
- Hub already tracks `workers`; this is mostly surfacing it.

### 2. 1v1 referee mode (worker-side) — the big prerequisite

This is what turns a worker from "an Evict FFA" into a real duel. Needed before
3-6 work properly.

- On host, read `duel.properties`.
- Lay out a **2-team** game (one core each) instead of FFA. Open question: small
  fixed map vs a shrunken Evict generation.
- Win conditions (decide in Discord): enemy core destroyed, `/die` surrender,
  optional time limit.
- On win: write `result.properties`, announce, send both players back to the
  hub (`Call.connect` to `hub.ip:hub.port`), then self-terminate (already empty
  -> exit).

### 3. Match start: both-present gate + 5s countdown

- Match does not "start" until **both** `duel.properties` UUIDs are connected.
- While waiting: players held in a neutral/frozen state (e.g. no unit, paused).
- When both present: 5-second on-screen countdown (`Call.infoPopup` / label),
  then assign teams + cores and unfreeze.
- Worker-side, depends on (2).

### 4. Disconnect handling: 60s pause + auto-return

- A player drops mid-match -> **pause the worker** (`Vars.state.set(paused)`)
  and show "Opponent left — waiting 60s" to the player who stayed.
- Rejoin within 60s -> resume.
- 60s elapse -> resolve (decide: present player wins, or void/no-ELO).
- **Auto-return:** the hub registry knows `uuid -> active worker`. When a player
  reconnects to the **hub** during the window, the hub immediately
  `Call.connect`s them back to their worker (skip normal hub onboarding).
- Edge: if the worker already exited (gone > 60s), clear the registry on exit so
  the hub does not bounce them to a dead port; show "your match ended" instead.

### 5. `/die` on the worker + auto-return after a win

- On a worker, `/die` is always available (no leader/10-min gate) and means
  surrender -> opponent wins.
- After any win (surrender or core), both players auto-return to the hub after a
  short delay, then the worker exits. Same path as (2).
- Needs a duel-specific surrender, separate from the FFA `/die` in
  `RoundEndCommands`.

### 6. `/view` (`/v`): spectate a live match

- `/v` on the hub -> menu of active matches (from the registry) -> redirect to
  that worker's port.
- The worker sees a UUID that is **not** one of the two match players -> puts
  them on **derelict** with no unit -> free-cam spectator (no build/fight).
- Consider a spectator cap per match (network cost) and whether spectators can
  chat to players.

### 7. Always-admin UUID (testing only)

- On `PlayerJoin` (hub **and** worker), if `uuid == "KShKEMpin0wAAAAAanrlJQ=="`
  set `player.admin = true`. Works on duel servers too for testing.
- **Security note (important):** a hardcoded UUID is **spoofable** — Mindustry
  UUIDs are client-supplied. Fine for private testing, not for a public ranked
  server. Proper later solution: Mindustry's admin system (`admin add`, which
  also binds the usid) or a config-backed admin list. Mark this a clear TODO.

### 8. ELO + `/history` (ranked system)

- **ELO:** standard Elo. Decide starting value (DB default is **1000**; the
  screenshot shows ~1500 — pick one), K-factor, and provisional handling.
  `players` already has `elo` / `peak_elo` / ranked counters — reuse them.
- **Ranked vs custom:** the screenshot shows `mode: ranked` (ELO changes) vs
  `mode: custom` (no change). So we need a notion of ranked vs unranked matches.
  Decide how a match becomes ranked (is `/play` always ranked? a separate
  command/toggle? a flag in the challenge menu?). The `ranked` flag flows through
  `duel.properties` -> `result.properties`.
- **New table `duel_matches`:** id, timestamp, p1_uuid, p2_uuid, winner_uuid,
  ranked, p1_elo_before, p1_elo_after, p2_elo_before, p2_elo_after. (No map/mode
  stored — `/history` should not show them, per request.)
- **`/history`** (no abbreviation): show the requesting player's last N matches
  in the screenshot style — `win/loss vs <opponent>`, and for ranked rows the
  `before -> after (+/-delta)` per player. Decide own-only vs `/history <player>`,
  entry count, and chat-text vs a `Call.menu` panel.
- ELO + history writes happen on the hub from `result.properties`, via the
  existing async writer.

## Suggested order

- **Phase 0 — Foundation:** handshake file, result file, hub registry, worker
  reads its config. Nothing user-visible yet.
- **Phase 1 — Referee mode (2) + `/die`/auto-return (5):** real 1v1, clean
  return to hub. The biggest chunk.
- **Phase 2 — Start gate + countdown (3) + disconnect/auto-rejoin (4).**
- **Phase 3 — ELO + ranked/custom + `/history` (8).**
- **Phase 4 — `/view` spectators (6) + console management (1) polish.**
- Admin UUID (7) can land any time (tiny), with the security caveat noted.

## Open questions

Answered — see "Decisions (resolved)" above. Only the three "Still open" items
remain.

## Risks / notes

- **Most of this is the "real 1v1 mode" that does not exist yet** — bigger than
  the redirect plumbing already built.
- Public networking: each worker uses its own port; the whole range must be
  port-forwarded for remote players (local testing is unaffected).
- Pausing a hosted server pauses it for everyone present — that is the intended
  "match stops" behaviour, but verify it does not desync clients.
- Hardcoded admin UUID is spoofable (see 7).
- Worker staleness after a plugin update: `duel-workers/` must be deleted so
  workers re-provision with the new jar (already noted in `AGENTS.md`).
- Spectators and many concurrent matches multiply network/CPU; keep the worker
  cap (`maxWorkers`) and a spectator cap in mind.
