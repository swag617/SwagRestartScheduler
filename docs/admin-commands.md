# Admin Commands

All functionality lives under a single command, `/srestart` (base permission `swagrestart.command`). Every sub-command below is implemented in `RestartCommand`.

## Summary

| Command | Permission | Description |
|---|---|---|
| `/srestart now [reason]` | `swagrestart.command.now` | Starts a 3-second countdown and broadcasts immediately |
| `/srestart in <time> [reason]` | `swagrestart.command.now` | Starts a countdown for `<time>` (min. 10 seconds) |
| `/srestart cancel` | `swagrestart.command.cancel` | Cancels a pending **manual** restart |
| `/srestart status` | `swagrestart.command` | Shows the active or next-scheduled restart |
| `/srestart schedules` | `swagrestart.command` | Lists every schedule from `schedules.yml` with its next run time |
| `/srestart reload` | `swagrestart.command.reload` | Reloads `config.yml`, `schedules.yml`, warnings, grace period, performance triggers, backups |
| `/srestart gui` | `swagrestart.gui` | Opens the in-game GUI (players only) |
| `/srestart logs export` | `swagrestart.command.logs` | Reports the on-disk path of the CSV restart log |
| `/srestart web` | `swagrestart.web` | Posts a clickable link to the web config editor URL |

## Details

### `/srestart now [reason]`

Broadcasts the restart, logs the initiator to console, then starts a 3-second delayed manual restart task. `reason` defaults to `"Manual restart"` if omitted.

### `/srestart in <time> [reason]`

`<time>` accepts combined units: `30s`, `5m`, `1h`, `1h30m`, `2h15m30s`, or a bare number treated as seconds. Minimum is 10 seconds — shorter values are rejected. If a manual task is already running, it is cancelled and replaced. `reason` defaults to `"Scheduled manual restart"`.

### `/srestart cancel`

Only cancels a **manual** task (started via `now`/`in`, or by a performance trigger). If the active task was schedule-driven, this reports "nothing to cancel" — there is no command to skip a single scheduled occurrence.

### `/srestart status`

Shows either the currently-counting-down task (with source and manual/scheduled tag) or, if nothing is active, the next upcoming scheduled restart time and which schedule produces it.

### `/srestart schedules`

Prints every loaded schedule — name, enabled state, timezone, days, times, and its computed next-restart time — regardless of whether it's the one that will actually fire next.

### `/srestart reload`

Reloads `ConfigManager`, `WarningManager`, `ScheduleManager` (cancels the current *scheduled* task and re-queues), and — if present — `PerformanceTrigger` and `BackupManager`. A currently-running **manual** restart task is left untouched.

### `/srestart gui`

Player-only. Opens the [in-game GUI](core-features/in-game-gui.md) main menu.

### `/srestart logs export`

`export` is the only recognized argument right now. It doesn't actually export anything — it just prints the absolute path of the CSV log file (`plugins/SwagRestartScheduler/logs/restart-log.csv`) so you can go grab it yourself.

### `/srestart web`

Requires the [web config editor](core-features/web-editor.md) to be registered (SwagAPI installed, `web-editor.enabled: true`). Sends a clickable link; if the editor isn't currently available, reports that instead of a broken link.

## Tab completion

`/srestart <tab>` suggests all nine sub-commands. `/srestart in <tab>` suggests common durations (`30s`, `1m`, `5m`, `10m`, `30m`, `1h`, `2h`). `/srestart logs <tab>` suggests `export`.
