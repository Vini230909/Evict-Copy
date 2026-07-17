# Restart loop — server setup (do this once, on the server)

The plugin **cannot restart the server by itself.** A Java process can exit, but it
cannot relaunch itself cleanly. So the restart feature is split in two:

- **In the plugin (already done):** `RestartManager` + the `evictrestart` console
  command exit the JVM cleanly at a safe moment.
- **On your server (this document):** a small wrapper that **relaunches the server
  whenever it exits.** Set this up once and `evictrestart` becomes a full restart.

Without the wrapper, `evictrestart` just shuts the server down and it stays down.

---

## What the plugin does

| Console command | Behaviour |
|---|---|
| `evictrestart` | Queue a restart. It fires when **no duel worker is running** and either the round just ended (best — the new process generates the next round, nobody loses progress), the hub is empty, or the round is under 10 min old (30 s warning first). Otherwise it waits for the round to end. Running matches are never killed. |
| `evictrestart cancel` | Drop the queued restart (and any pending 30 s warning). |
| `evictrestart now` | Hotfix path: announce, then exit in 1 s, killing any running matches. |

On the next boot the hub's `refreshWorkerJars()` pulls every existing duel worker
onto the new jar, so a single hub restart updates the whole fleet.

---

## Option A — bash `while` loop (simplest)

Create `run.sh` next to the server jar:

```bash
#!/usr/bin/env bash
set -u
cd "$(dirname "$0")"

# Relaunch whenever the server exits. Ctrl-C / SIGTERM breaks the loop.
while : ; do
    echo "[run.sh] starting server $(date)"
    java -jar server-release.jar
    code=$?
    echo "[run.sh] server exited with $code $(date)"

    # A clean exit (0) is a restart request -> loop and relaunch.
    # Any other code is a crash: still relaunch, but pause so a crash-loop
    # doesn't spin the CPU.
    [ "$code" -ne 0 ] && sleep 5
done
```

```bash
chmod +x run.sh
./run.sh          # foreground
# or keep it alive in a detached session:
# tmux new -s mindustry -d './run.sh'
```

> Trap note: pressing Ctrl-C usually kills both `java` and the loop. If yours only
> kills `java` and the loop relaunches, stop the server with a real shutdown
> instead (or `tmux kill-session -t mindustry`).

## Option B — systemd (recommended for a real server)

`/etc/systemd/system/mindustry.service`:

```ini
[Unit]
Description=Mindustry Evict server
After=network.target

[Service]
Type=simple
User=mindustry
WorkingDirectory=/opt/mindustry
ExecStart=/usr/bin/java -jar /opt/mindustry/server-release.jar
Restart=always
RestartSec=3
SuccessExitStatus=0

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mindustry
# console access:
sudo journalctl -u mindustry -f          # logs
# to send console commands, run the server under a console multiplexer
# (screen/tmux) instead, or use an RCON/console bridge.
```

`Restart=always` relaunches on the clean exit that `evictrestart` triggers **and**
on a crash. Use `Restart=on-success` if you want *only* `evictrestart` to relaunch
and a crash to stay down for investigation.

---

## Updating the plugin/jar safely

The running JVM holds the **old** jar open, so you can stage a new one while it runs:

```bash
# 1. upload beside the server as a temp name so a half-written file is never booted
scp EvictMapGenerator.jar  server:/opt/mindustry/config/mods/EvictMapGenerator.jar.new
scp server-release.jar     server:/opt/mindustry/server-release.jar.new

# 2. atomically move into place (mv on the same filesystem is atomic)
ssh server 'cd /opt/mindustry && mv config/mods/EvictMapGenerator.jar.new config/mods/EvictMapGenerator.jar && mv server-release.jar.new server-release.jar'

# 3. IMPORTANT (per CLAUDE.md): drop stale workers so they re-provision from the new jar
ssh server 'rm -rf /opt/mindustry/duel-workers'

# 4. in the server console, queue the restart; it fires at the next safe moment
#    evictrestart
```

Because the move is atomic and staged as `*.new` first, the `while` loop / systemd
can never boot a half-uploaded jar.
