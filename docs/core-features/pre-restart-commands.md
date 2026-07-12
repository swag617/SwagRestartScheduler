# Pre-Restart Commands

Runs console commands at configured offsets before a restart. Enabled by default (`pre_restart.enabled: true`).

```yaml
pre_restart:
  enabled: true
  commands:
    - delay: 300
      command: "broadcast &cServer restarting in 5 minutes!"
      executor: "console"
    - delay: 60
      command: "broadcast &cServer restarting in 1 minute! Save your progress!"
      executor: "console"
    - delay: 5
      command: "save-all"
      executor: "console"
```

- `delay` — seconds *before* the restart that the command should fire
- `command` — dispatched via `Bukkit.dispatchCommand(Bukkit.getConsoleSender(), ...)`
- `executor` — only `"console"` is recognized; there is no player-executor mode

Commands are (re-)scheduled every time a countdown starts, and all pending commands are cancelled if the restart itself is cancelled. If a command's fire time has already passed by the time it's scheduled (e.g. a short `/srestart in 10s` with a `delay: 300` entry), it runs immediately instead of being skipped.

## PlaceholderAPI substitution

If PlaceholderAPI is installed, each command string is run through `PlaceholderAPI.setPlaceholders(null, command)` before dispatch — using a `null` (console/offline) player context. This means player-specific placeholders won't resolve to anything meaningful here; it's intended for server-wide placeholders. If PAPI isn't installed, or substitution throws, the command runs unmodified.
