# Code rules

The one file about *how the code is shaped*. Short on purpose: read it whole,
every session, before touching any code. Gameplay lives in `CLAUDE.md`; the
reasons behind decisions live in `docs/`. Neither belongs here.

**Reference:** [fish-commands](https://github.com/Fish-Community/fish-commands)
(TypeScript, ~14k lines, 146 commands). That is what this plugin should look
like, in Java: flat, named after things, commands as data, one config, events
wired directly, one-line comments. When in doubt, open fish-commands and do
what it does.

## The rules

1. **Flat.** `src/Extinction/` holds files named after *things*
   (`Players`, `Teams`, `Bans`, `Extinction`, `Discord`), at most one level of
   subfolders. Target: about 30 files. A file is at most 600 lines.

2. **Commands are data, in one place.** Every command is one entry in
   `commands/Player`, `commands/Admin` or `commands/Console` — args,
   description, permission, handler. A command is never its own class. A
   command file contains commands and nothing else.

3. **One config, no getters.** All settings are fields of one config object
   that reads and writes the properties file. Adding a setting adds one field.
   It never adds a getter, a setter or a command.

4. **Events are wired in the plugin, directly.** The plugin file is a list of
   `Events.on(...)` and the code they call. No class whose job is to hold other
   classes; no `*Manager` that only forwards. Reading one event handler shows
   what happens, without following it three files away.

5. **Comments are one line.** A file starts with one sentence saying what it
   contains. No comment inside code is longer than two lines. A *why* that
   needs a paragraph goes into `docs/` and the comment links it.

6. **Nothing new goes into the old shape.** No new field in `EvictSettings`,
   no new command in `ConsoleCommands`, no new `*Manager`. New code is written
   in the new shape, next to the old, and the old is moved over piece by piece.

## How a session works

- Read this file. Then read only the files the task touches.
- A change that adds a feature also does not add a file to the old shape (rule 6).
- A change that moves code changes no behaviour: same commands answer, same
  properties file, same output. Move-only changes are the only kind of
  restructuring allowed; "improve while moving" is two changes.
- A big feature is fine; it is still written in the new shape and may take
  several sessions. If it needs to *change* old code, that piece is moved into
  the new shape first (move-only, its own deploy), then changed.
- Build. Run the check script. Red means the change is not finished.
- **This file and the check script are never edited by a session.** They change
  only when the owner asks, in a commit that changes nothing else. A diff that
  touches them unasked is a mistake, not a fix.

## What "done" means

For a session: build green, check green, and the owner can open the file tree
and see that the change made the tree *more* like fish-commands, not less. If
the tree is not judgeable, the change is not done.

For the owner, after uploading the jar and restarting: server boots, one round
runs. Only then is the change really done — a session can't verify this part.
