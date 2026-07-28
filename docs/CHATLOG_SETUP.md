# Discord chat mirror — setup

The chat mirror copies what players do on the servers into Discord: one
channel for the hub, one per match-server port. It is sent by a Discord bot,
so the whole channel structure can be created by the plugin itself.

**These channels are staff-only.** They carry every line players type,
including messages the word filter deletes. Never open them to members.

---

## 0. Deploy the plugin

Copy `EvictMapGenerator.jar` into the server's `config/mods/` and restart.
On startup the plugin creates the secrets file:

```
config/evict.env
```

It contains every key it needs, empty:

```
DISCORD_CHAT_BOT_TOKEN=
```

## 1. Create the bot

<https://discord.com/developers/applications> → **New Application** (any
name, e.g. "Evict Logs") → tab **Bot** → **Reset Token** → copy it. Discord
shows the token exactly once.

The "Privileged Gateway Intents" below are **not** needed — the bot never
reads anything, it only posts.

## 2. Put the token in the file

Open `config/evict.env` over FTP or your panel and paste the value after the
`=`:

```
DISCORD_CHAT_BOT_TOKEN=MTM4NzQ...your.token
```

No quotes needed, no spaces around the `=`.

> **Never type the token into the server console.** Everything typed there is
> written to the server log and the start script's screen log permanently.
> That is why there is no `evictchatlog token …` command.

## 3. Invite the bot

Developer Portal → **OAuth2** → **URL Generator** → scope `bot` → permissions
**Manage Channels**, **View Channels**, **Send Messages**. Or use the URL
directly, with `CLIENT_ID` from "General Information":

```
https://discord.com/oauth2/authorize?client_id=CLIENT_ID&scope=bot&permissions=3088
```

Open it and pick your Discord server. **Manage Channels** may be removed
again after step 5 — it is only needed to create the channels.

## 4. Copy the server id

Discord → Settings → **Advanced** → **Developer Mode** on. Then right-click
your server icon → **Copy Server ID**.

Unlike the token, this is not a secret, so the console is fine for it.

## 5. Three console commands

```
evictchatlog reload
evictchatlog setup <server-id>
evictchatlog test
```

* `reload` reads the token from `config/evict.env`.
* `setup` creates the category **Evict Logs** with `hub` and one `port-<n>`
  channel per pool port, adopts their ids automatically, and denies
  `@everyone` access to all of them. Takes a few seconds — Discord rate-limits
  channel creation, so they are created one after another.
* `test` posts one line into every wired channel.

## 6. Give your staff role access

After setup the channels are visible to **nobody** except the bot. In
Discord: right-click the **Evict Logs** category → Edit Category →
Permissions → add your admin/staff role with **View Channel**. The channels
inside inherit it.

The bot deliberately does not do this itself: it cannot know which of your
roles is the staff role.

---

## Checking the wiring

`evictchatlog` with no argument is the checklist:

```
bot token: loaded from config/evict.env
hub: on, last success 3s ago
port-6568: on
port-6569: NOT SET
...
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| `token: NOT SET` | Value missing in `evict.env`, or `evictchatlog reload` not run |
| setup: "bot token was rejected (401)" | Token mistyped, or reset in the portal since |
| setup: "may not manage channels (403)" | Bot was invited without **Manage Channels** |
| setup: "no such server (404)" | Wrong server id, or the bot is not a member of it |
| A channel shows `BROKEN` | Bot has no permission to post there (hand-wired channels) |
| A channel stays silent on `test` | Hand-wired with the wrong channel id |

## Later on

* **More ports** (`evictduelserver` with a higher `maxWorkers`): run
  `evictchatlog setup <server-id>` again. Existing channels are adopted; only
  the missing ones are created.
* **Rotate the token**: new value in `evict.env`, then `evictchatlog reload`.
  Channels disabled by the old token start working again immediately, without
  a restart.
* **Wire a single channel by hand**: `evictchatlog hub <channel-id>` or
  `evictchatlog 6568 <channel-id>` (channel id via Developer Mode →
  Copy Channel ID).
* **Switch everything off**: `evictchatlog off`. Drops the channel wiring;
  leaves the secrets file and the Discord channels alone.

## What ends up in the channels

Only what players did — never automatic output like countdowns, captures or
Extinction warnings.

* chat, and commands as typed (`Name: /play`)
* joins and leaves
* ban announcements
* per match on its port channel: a start entry (mode + rosters), the chat, and
  an end entry (winner, loser, duration, how it ended; ranked matches add the
  ELO movement)
* per hub round: a start line and an end entry (winning team with its leader,
  duration, and how it was won)

See `CLAUDE.md`, section "Discord chat mirror", for the full specification.
