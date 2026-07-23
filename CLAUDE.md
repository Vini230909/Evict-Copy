# CLAUDE.md — EvictMapGenerator

Server-side Mindustry plugin (game v157.4, Java 17 source) for Evict-style persistent PvP on a procedurally generated hex map. Runs on a dedicated server; clients install nothing. Current version **1.4.5** — `plugin.json` `version` and the startup revision string in `EvictMapPlugin` must always match.

One jar, two roles:
- **Hub** — the normal Evict FFA server players connect to.
- **Worker** — an on-demand match server launched with `-Devict.duelWorker=true`; runs normal Evict plus the `DuelWorker` referee for one `/play` match and self-terminates (`System.exit`) when empty.

## Build & deploy

```bash
./gradlew jar   # → build/libs/EvictMapGenerator.jar
```

No system `java`/`gradle` on this machine — point `JAVA_HOME` at the VSCode Java extension's bundled JDK 21 first (glob `~/.vscode/extensions/redhat.java-*/jre/*`). Build after every code change; fix compile errors immediately.

Deploy: copy the jar into the server's `config/mods/` and restart — nothing else. `duel-workers/` must **not** be deleted: every worker spawn re-copies `config/mods` into its folder and `refreshWorkerJars()` updates stale server jars on hub startup, so workers pick up a new plugin automatically. The server console is not a shell: set the port with `config port <n>`, host with `host evict-map pvp`; `evictauto` defaults ON, so hosting auto-generates an Evict round.

## Working rules

- Preserve existing gameplay unless a gameplay change is explicitly requested. This file is the canonical spec — keep it in sync when behavior changes.
- Small, focused changes; no god classes; command implementations stay out of `EvictMapPlugin`.
- The hub is the single DB writer. Workers report results via files and never touch SQLite.
- Before declaring a version finished: clean build, startup revision matches `plugin.json`, every changed and newly required file included.

## Code map (`src/vini/evictmap/`)

Package layout (reorganised in 1.4.0): `core/` shared infrastructure, `gen/`
generation + settings, `round/` team/round systems, `data/` persistence, `gameplay/`,
`duel/`, `commands/`, `metrics/`, `discord/`. `EvictMapPlugin`, `RestartManager` and
`PlayerNameFormatter` stay at the package root.

Core infrastructure (`core/`) — reusable, gameplay-agnostic:
- `text/Colors`, `text/Text`, `text/Msg` — named colour tags + a fluent coloured-message builder (`Text.of().scarlet(...).player(p)...sendTo(...)`) + a small reusable-message catalogue. Replaces hundreds of inline `[scarlet]…[]` literals.
- `cmd/` — the command framework: `Command`/`Command.Builder` (declare a command as data), `Arg`/`ArgType` (typed `"name:type"` args, e.g. `"target:player"`), `Perm`, `CommandContext` (injected sender + typed getters + `reply/success/error/fail`), `CommandError`, `Commands` (registers into Mindustry's `CommandHandler`, doing perms/parsing/error-catching centrally).
- `io/PropertiesFile` — load/save `Properties` with mkdirs + typed getters.
- `util/Players` (online lookup by uuid/name), `util/PluginLog` (`[EvictMapGenerator]`-prefixed logging), `util/Ticks` (second↔tick).

Lifecycle:
- `EvictMapPlugin` — composition root: `bootstrap()` + `configureWorkerReferee()` + event wiring (broken out of the old monolithic `init()`), manager construction, round startup, auto next-round reset; on a worker also wires the referee and the empty-server self-exit.
- `gen/EvictRuntimeState` — auto-generation state, current/next map seed.
- `gen/EvictSettings` — loads/persists `config/evict-map-generator.properties`.
- `RestartManager` — implemented `evictrestart` graceful-restart flow (queue / `cancel` / `now`); the plugin only exits cleanly, an external start-script loop relaunches it — see `docs/RESTART_LOOP.md`.
- `metrics/MetricsReporter` — throttled hub metrics log line (players, duel players, active matches).
- `discord/` — hub-only live status message in a Discord channel (see Discord status below): `DiscordStatusReporter` (30 s loop, ladder cache, offline notice), `DiscordWebhook` (create/edit transport), `StatusMessage` + `StatusSnapshot` + `DiscordJson` (payload), `DiscordFormat` (name cleaning, durations).
- `gameplay/RulesApplier` — fixed PvP rules, banned blocks, building-vs-building bullet damage scaling, disables Alpha/Beta/Gamma core-unit combat damage while keeping their building/mining.

Generation:
- `HexGrid` — shared hex-grid geometry constants (rows/columns, hex radius).
- `EvictTerrainGenerator` — hex geometry, filled hexes, walls/passages, neutral Fallen cores, final-seven Extinction protection.
- `ResourceGenerator` — ores, water, oil, resource fallbacks.
- `StartLoadout` — one-time starting schematic and resources.
- `CoreMarkerFloor` — floor marker under cores.

Round systems:
- `TeamManager` — personal teams, Fallen handling, leaders, claims, eliminations, surrender, victory checks, Extinction terrain queue.
- `HexSlot` — one hex cell: immutable geometry (col/row, center tile, protected sides) + live capture/Extinction state. Shared by TeamManager, CoreCapture, terrain gen and Extinction.
- `HexGeometry` — pure offset-grid math over `HexSlot` (tile distance, BFS grid distance, adjacency, nearest-slot); stateless and unit-testable.
- `TeamColors` — picks new team ids with colours distinguishable from those in play.
- `CoreCapture` — destroyed-core lifecycle: immediate captured-hex cleanup, 5 s replacement delay, second anti-abuse cleanup, replacement Core Shard, verified core placement (also used by surrender's Fallen restore).
- `gameplay/AttritionManager` — capture attrition and range attrition.
- `gameplay/AttackManager` — team-scoped `/fullassault` (`/fa`).
- `gameplay/ExtinctionManager` — timed late-game ring collapse, center-core final phase, overtime.
- `InviteManager` — join requests, claimed players, leader-managed invites.
- `PlayerDataManager` — async SQLite (single background writer thread): profiles, playtime, FFA counters, ranked counters + ELO, `/history` match rows.
- `EloCalculator` — pure Elo math (start 1000, K-factor 32); `PlayerDataManager` persists results.
- `RankManager` — tournament name tags, worker-synced admin recognition.
- `PlayerNameFormatter` — live player names for chat/menus: first `[#xxxxxx]` name colour if present, otherwise team colour.

Matches:
- `duel/DuelServerManager` — hub-side worker pool: reserves ports, provisions worker folders, spawns worker processes, redirects rostered players, frees slots on exit.
- `duel/DuelWorker` — worker-side referee: handshake, start gate, countdown, disconnect pauses, result file, periodic status file.
- `duel/DuelChat` — worker-side chat routing for modes that `restrictsSpectatorChat()` (Ranked): the chat filter + inverted `/t` override.
- `duel/MatchMode`, `duel/modes/` — mode ids and per-mode rules (1v1, Ranked, Teams, FFA, Training, Sandbox).

Commands (`commands/`):
- `ClientCommands` — single registration point for player chat commands.
- `ConsoleCommands` — console commands (`evict*`), stored player lookup, `evictelo`, `evictrestart`; declared on the `core/cmd` framework (the old ~319-line `register()` is now a flat list of `command(...).run(...)`).
- `DuelCommands` — `/play` `/p` menus.
- `HelpCommands` — `/help`.
- `HistoryCommands` — `/history` `/h`.
- `InfoCommands` — `/info`.
- `LeaderboardCommands` — `/leaderboard` (`/top`, `/lb`): ranked ELO ladder over the player DB.
- `RoundEndCommands` — `/die`, `/over`.
- `RoundTimeCommands` — `/time`.

## Gameplay spec

### Teams
- Fallen team is always team `#14`; Fallen itself cannot be claimed.
- New players get a personal team and one protected starting hex; every building in that hex (including Fallen ones) is wiped before the start schematic + core are placed.
- Eliminated players become Fallen; Fallen players can still chat and move their camera; Fallen can request teams via `/invite`.
- Team leaders are the players who created their teams.

### Captures
- Core dies → ownership changes logically immediately; buildings inside the captured hex are deleted immediately.
- Replacement Core Shard appears after 5 s; buildings created during those 5 s are deleted again right before replacement (double-wipe protection — preserve it).
- Captured cores become Core Shards. `/die` surrender restores Fallen Nucleus cores in surrendered hexes.
- Replacement cores are verified after placement; an unverified/missing core does not count as owned and cannot block victory as a phantom core.
- Victory, elimination and `/over` counts use actual existing core blocks; pending captures still count immediately for their pending owner.
- A core is matched to its hex by footprint, not exact origin tile (keeps the even-sized 4x4 Foundation tied to its hex after upgrades).

### Attrition
- Capture attrition: once on core capture, normal units within the 40-tile capture radius, tier-based values. Console: `evictattritioncore [t1-3] [t4] [t5]`.
- Range attrition: every 5 s, units ≥ 2 hexes from an owned core, same percent for all eligible tiers, default 20%. Console: `evictattritionrange [percent]`.
- Core-spawned player units never receive attrition; core units build/mine but deal no combat damage.
- Building-fired bullets deal 10% damage to buildings, normal damage to units.
- Both attrition settings persist across restarts.

### Player data (`config/evict-players.db`)
- Async writes on one background thread; profiles keyed by UUID; no IP addresses stored. IP lookup for bans doesn't need them: console `evictplayerinfo` resolves name/UUID via the plugin DB and prints last + all known IPs from Mindustry's built-in admin store (which already tracks every IP per UUID) — feed those to `ban ip`.
- Stored: last name, first/last seen, one total playtime, normal + ranked match counters, ELO/peak ELO; all observed names per UUID in `player_names`. Match stats exist only for normal and ranked matches — the main-lobby FFA round has no stats (legacy `ffa_*` / `ranked_playtime_ms` columns in old databases are ignored).
- Ranked wins/losses/played, ELO and peak ELO update only after a **Ranked** match (casual 1v1 never touches them); match rows store both players' before/after ELO.
- Normal wins/losses/played count every competitive `/play` match: casual 1v1, Teams and `/play` FFA (Random Teams records as Teams; Training/Sandbox never record). Ranked matches count only in the ranked counters. `/info` shows Total playtime, Normal, Ranked and ELO.
- Every `players` column newer than the original baseline has an `ALTER TABLE` guard, so databases created by old plugin versions upgrade in place instead of failing writes silently.
- One-time stats repair (tracked via `PRAGMA user_version`): pre-1.4 builds counted every casual 1v1 in the ranked counters, so on first startup the plugin recounts normal/ranked counters from the `duel_matches` rows and replays all ranked rows chronologically through `EloCalculator`, rewriting each row's before/after ratings and every player's ELO/peak (manual `evictelo` values from before the repair are replaced).
- New players start at ELO 1000; peak ELO only ever rises (manual `evictelo` sets never lower it).
- Playtime flushes at round start, on leave and on shutdown; `/info` and `evictplayerinfo` add live unpersisted session time.
- Playtime counts time on the match servers too. A worker has no database of its own: it reads the hub's (`../../config/evict-players.db`) so `/info`, `/leaderboard` and `/history` show real numbers on a match server, and it never writes. It publishes a running per-UUID total in `status.properties`; the hub diffs it every poll (~2 s) plus once more on worker exit and credits the growth, so a repeated or missed poll can neither double-count nor lose time. A reused worker folder's old status file is deleted at spawn so its totals cannot be credited twice.
- Lookup order for `/info [name]`, `evictplayerinfo`, `evictelo`: partial latest-name match first; old names and UUIDs only if no latest-name match. Ambiguous `evictelo` names list candidates and change nothing.
- `/info` is public, never shows UUIDs to normal players — a server admin viewer additionally gets the subject's UUID (IPs stay console-only). No argument opens a clickable online-player picker; a name that matches exactly one stored player prints their stats, and a name matching several opens the same picker built from the matches (newest-seen first, capped, refine for the rest) instead of a text list.

### Commands
- `/fullassault` (`/fa`) — team-scoped toggle (never global), updates every 5 s, sends eligible unattended combat units to the nearest enemy core; ignores player-controlled units and units on mine/assist/rebuild/repair.
- `/invite [number]` — only the team leader accepts. When a team loses its final core, the team that destroyed the most of its cores claims it (tie → destroyer of the final core). Claimed players can join only their claimant's team.
- `/die` — leader-only surrender, available only after the round has run 10 minutes; before that it must not show a countdown. At 10 minutes a global status message lists active match player names only (no team/core counts, no mention of `/die`). No confirmation. Instantly destroys all team buildings/units, converts hexes to Fallen, restores Fallen Nucleus cores. If exactly one active personal team destroyed the most surrendered cores, players and claims transfer to it; otherwise claims are released.
- `/over` — any personal-team player; needs ≥ 50% of all cores; teams that never owned more than one core don't block it; teams that expanded must be fully eliminated. No confirmation. Disabled once the 10-minute Extinction warning begins.
- `/time` — round runtime since map start + the player's connected time since first join this round (join records remembered across startup scans; fallback is round start).
- `/help [page]` — paginated; never lists itself; aliases fold into their target's row (e.g. `/history (/h)`); `/restart` is listed with its commentator/admin description and stays permission-gated. There is no separate dev help — former dev chat commands are console-only (`evictattritioncore`, `evictattritionrange`, `evictwall`, `evictcorecap`).
- `/history` (`/h`) — a player's 1v1, Teams and FFA matches, most recent first.
- `/leaderboard` (`/top`, `/lb`) — the ranked ELO ladder (players with ≥ 1 ranked match), highest first; optional count (1–25, default 10).

### Matches (`/play`, `/p`)
Game-mode menu: `1v1`, `Ranked`, `Teams`, `Random Teams`, `FFA`, `Training`, `Sandbox`.
- **1v1** — opponent picker (two per row, bottom cancel), accept/decline challenge menu. Casual: unranked `/history` entry (win/lose, no ELO), no ranked counters.
- **Ranked** — same flow as 1v1 but rated; the only ranked mode. Updates both players' ELO/peak ELO and ranked counters, stores a ranked `/history` entry with before/after ratings (recorded via `result.properties` on the hub). Behaves like a 1v1 on the worker.
- **Teams** — up to 8 rosters built purely via pick menus; challenger starts on Team 1; picks fill the current team. `Next team` (needs ≥ 1 player on the current team; hidden at the 8-team cap) and `Done` (empty trailing team dropped; ≥ 2 teams required). No player picked twice; uneven sizes allowed. Everyone gets an accept/decline invite; any decline or disconnect cancels for all. A team knocked out mid-match (3+ teams) is freed like an FFA loser. Stored unranked in `/history` (all rosters, win/lose).
- **Random Teams** — challenger picks team count (2-8), then picks a single player pool like FFA (≥ team-count players incl. challenger). After all accept, the pool shuffles into balanced teams (first teams get the extra player; sizes differ ≤ 1). Rosters not announced. Hub-only: launches as a regular `teams` match.
- **FFA** — pick any number of players (Done button), same invite flow, everyone on their own team. Knocked-out players become spectators: the match no longer waits for them, `/v` returns them to the lobby, and the hub lets them join the normal round (worker publishes an `out` list in `status.properties`). Stored unranked in `/history` (all participants, win/lose).
- **Training** — solo on a worker; nothing won/lost; `/die` ends it (no ELO) and returns to hub.
- **Sandbox** — Training + infinite resources; `/view` spectators may `/invite` to request joining, and anyone already in the match (owner or a previously promoted player) accepts with the normal leader `/invite` flow (spectator promoted to participant) — not just the original owner.

All modes run the same generated Evict map and worker rules: wait-for-everyone start gate, 5 s countdown, disconnect pause with rejoin window.

Chat routing (workers only): every mode except **Ranked** uses normal global chat for everyone. In a **Ranked** match only the two duelists chat on global; viewers and casting admins have their normal chat routed to the spectators' chat instead (so nobody can leak information to the players). A casting admin (`RankManager.canRestartMatches` — server admin or ranked commentator) reaches global with an inverted `/t`: their normal chat goes to spectators, and `/t` broadcasts to everyone. Plain viewers have no path to global. The hub's `/t` is left vanilla; the worker overrides `/t` for this.

Worker infrastructure:
- One Mindustry process hosts one game, so each match gets its own worker process — spawned on demand, never idle.
- Handshake `duel.properties` (hub → worker): `mode`, `team.count`, `team.N` UUID lists. Result `result.properties` (worker → hub): `mode`, `winner.uuid(s)`, `loser.uuid(s)`, `reason`. Status `status.properties` (worker → hub, rewritten every 2 s): state, game time, connected players, `out`/`participants`/`owner` and `playtime` (`uuid:millis` running totals). Only `ranked` results record as ranked; `1v1`/`teams`/`ffa` become unranked `/history` entries; Training/Sandbox are never recorded.
- On accept, `DuelServerManager` reserves a free port, provisions `duel-workers/duel-<port>/` from the hub's jar + `config/mods` + `config/maps` if missing, launches `java -Devict.duelWorker=true -jar <jar>`, injects `config port` and `host` over stdin, polls the port, then redirects players with `Call.connect`. Spawning/readiness wait run on a background thread; redirects and slot bookkeeping on the main thread.
- Worker self-exits when empty (frees the hub slot) via a plain `System.exit(0)`; backstops: startup grace (no player ever arrived), 110-min max lifetime, JVM shutdown hook. A close from a decided match (win/lose or solo `/die`, which already sent everyone back with their own 5 s message) exits straight away. An abandoned worker still being watched instead counts down out loud first: a per-second ticking HUD popup (restart-countdown style) for 5 s, then a 3 s hold at zero, then it exits. The countdown ticks on the real-time scheduler (not `Time.run`) so it still runs when the match sits in an unresumed disconnect pause.
- Up to `maxWorkers` matches at once (1-10); all busy → `/play` says try again shortly. A blank duel-server IP leaves `/play` inert. Closing menus / losing the opponent cancels cleanly; stale challenges drop on leave.
- Remote players need the whole worker port range forwarded.

### Extinction
- Timer starts when the generated round starts. `01:20:00` global 10-min warning + `/over` disabled; `01:25:00` 5-min warning; `01:29:00` 1-min warning; `01:30:00` Extinction begins.
- Rings collapse farthest → nearest; the next ring starts 90 s after the prior ring finishes. Within a ring, core/hex collapse has no artificial delay; terrain-to-Space conversion is throttled separately.
- If all surviving cores belong to one team during Extinction, that team wins immediately.
- Final phase: the center hex + its six neighbours are protected from procedural filling. When only those 7 hexes remain, a 4-minute center-core phase begins; whoever owns the middle core after 4 minutes wins — including Fallen (then the round resets normally).
- Terrain streaming: `extinction.terrainChangesPerTick` in the properties file — Space-floor conversions per tick, default 120, range 1..4096.

### Discord status
Hub-only. One webhook message in a Discord channel (`#serverstatus`), edited in place every **30 s** — never a new message, and an edit notifies nobody. Two embeds: server status (player count vs. the server's slot cap, lobby/match split only while a match runs, round runtime + Extinction countdown, running matches, and a `⚠️ Restart queued` field that appears only while a restart is queued) and the top-10 ranked ELO ladder.
- Refresh is unconditional, not change-detected. The closing `Updated <t:…:R>` line is a Discord relative timestamp that counts up client-side, so a healthy server always reads "seconds ago" and a stuck one is visible by the number growing.
- Matches are identified by **pool slot** (1..`maxWorkers`), never by port, with mode label, rosters and worker uptime. Names come from the spawn-time roster (`DuelServerManager.matchStatuses()`), so a match still lists everyone after a disconnect.
- The ladder is re-queried every 5 min, and immediately whenever a stored rating changes (a ranked result or `evictelo`) — otherwise it would disagree with `/info` and `/leaderboard` until the slow refresh came round. The message still redraws every 30 s from the cache.
- Player names are user-controlled, so every name passes `DiscordFormat.playerName`: strip Mindustry colour tags → strip control chars → collapse whitespace → truncate to 24 → backslash-escape `\ * _ ~ ` |` (escape last, so a cut never splits an escape pair). Independently, every payload sets `allowed_mentions: {"parse": []}` — that, not the escaping, is what stops a player named `@everyone` from pinging the whole Discord server twice a minute.
- Message id is persisted (`discord.message.id`) so restarts keep editing the same message. A 404 while editing means someone deleted it → post a fresh one; a 404 while creating means the webhook is gone → stop and log. 401/403 disables; 429 backs off for `retry_after`; transient failures are logged on the 1st and then every 20th.
- Clean shutdown posts `🔴 Offline` from a JVM shutdown hook (blocking send, 3 s budget), so `evictrestart` and normal stops are covered. A hard crash cannot reach it — that is what the counting timestamp is for.
- Transport is the JDK `java.net.http.HttpClient`: Arc's `Http` (and `HttpURLConnection` under it) has no PATCH, which Discord's message edit requires.

### Generation
Filled hexes: min 6, max 12, center bonus up to 12%; the final-seven Extinction hexes must never be filled. Chances: border 11%, second ring 3.5%, inner 1%; chain start 22%, chain continue 48%, max chain length 3.

Water: configured tries per hex (not per-core fallback); decimal tries give a fractional chance for one extra try (`4.3` = 4 tries + 30% for a fifth; the console accepts `4.3` or `4,3`). Patches are noise-guided inside each hex and may share tiles with ore/oil overlays (hard-coded, no setting). Each patch has a configured percent chance to use the large tile count instead of the normal one.

## Console settings (persisted in `config/evict-map-generator.properties`)

- Ores: `evictcopper|evictlead|evictcoal|evicttitanium|evictthorium|evictscrap [scale] [threshold] [octaves] [falloff]`
- Water: `evictwater [tries-per-hex] [normal-patch-tiles] [large-patch-percent] [large-patch-tiles]` — default `evictwater 1 3 13.33 8`
- Walls: `evictwall [full-wall] [small-wall] [open] [passage]`
- Build speed: `evictbuildspeed [multiplier]` — unit-factory build speed each round; no argument shows current; default `1.4`; stored as `rules.unitBuildSpeedMultiplier` and copied into every worker; applies next generated match.
- Duel pool: `evictduelserver [ip] [basePort] [maxWorkers] [map]` — no argument shows config; omitted values keep current. `ip` is what clients reach workers at (blank = `/play` inert); workers use `basePort .. basePort+maxWorkers-1`. Defaults: ip unset, basePort `6568`, maxWorkers `4`, map `evict-map`, worker jar `server-release.jar`.
- Discord status: `evictdiscord [url/off/test]` — no argument reports the current wiring (message id, last success, last error); a webhook URL adopts it and posts a fresh message; `off` posts Offline and stops; `test` refreshes immediately. Stored as `discord.webhook.url` + `discord.message.id`; changing the URL clears the id. Setup is Discord-side: create the channel, Integrations → Create Webhook, paste the URL.
- Player data: `evictplayerinfo [name/uuid]` (single match also prints lastIP + all known IPs from Mindustry's admin store, for `ban ip`), `evictelo <name/uuid> <value>` (see Player data above).
- Restart: `evictrestart` queues a graceful update restart (fires when no worker runs and the round ends / hub is empty / round is < 10 min old after a 30 s on-screen countdown); `evictrestart cancel` drops it, also mid-countdown (hides the HUD, announces the cancel); `evictrestart now` shows a 10 s countdown, then exits. Countdowns tick per second in a HUD popup (3 s popup lifetime) and post milestone seconds to chat (every 10 s plus the last five). The exit closes the network, then hard-exits via `System.exit(0)` (the duel-worker way; shutdown hooks still flush the player DB), with a 10 s `Runtime.halt(0)` guard should a shutdown hook ever wedge; the external start-script loop relaunches the server.

## Safety notes

- No large one-tick network bursts; never send thousands of `setFloorNet` packets in one tick — terrain floor changes stay throttled.
- Extinction removes logical ownership, cores, buildings and units immediately; only the visual terrain conversion streams gradually.
- Preserve the double-wipe capture protection.
