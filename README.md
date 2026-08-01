# Evict Map Generator

Server-side Mindustry plugin for Evict-style PvP on a procedurally
generated hex map. Players connect and play — nothing to install.

- Game: Mindustry v157.4
- Version: 1.9.1

## The round

- The map is a hex grid, new seed every round.
- Every player gets a personal team and one protected starting hex,
  with a start schematic and starting resources.
- Unclaimed hexes belong to the neutral Fallen team.
- Kill a core and the hex is yours; a Core Shard replaces it after 5 s.
- Lose your last core and you become Fallen — you can still watch,
  chat and ask for a team with `/invite`.
- Units far from your cores slowly bleed away (attrition).
- Core units build and mine, but do no combat damage.
- After 1:30 h Extinction starts: the outer rings collapse ring by
  ring until only the middle seven hexes are left, then a 4-minute
  fight over the center core decides it.

## Matches

`/play` starts a real match on its own server, spawned on demand:

- **Unranked** — casual duel, nothing at stake
- **1v1** — the rated duel: ELO and the ladder
- **Teams** / **Random Teams** — up to 8 rosters
- **FFA** — everyone for themselves
- **Training** / **Sandbox** — solo, nothing recorded

Everyone waits for everyone, 5 s countdown, disconnects pause the
match. Results and playtime go back into the stats database.

## Player commands

```text
/help          /play (/p)     /view (/v)
/info [name]   /history (/h)  /top [count]
/invite [n]    /fullassault (/fa)
/die           /over          /time
/ban [name]    (admin)
```

## Credits

Evict is run by three owners, equally — nobody is the boss:

- **actualquak**
- **Vini2309** — also the copyright holder of the code
- **Virogens**

## License

© Vini2309. All Rights Reserved. 2026 — see [LICENSE](LICENSE).
