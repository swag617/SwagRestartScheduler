# Restart Scheduling

## Named schedules

`schedules.yml` can define any number of independent schedules, each with its own `enabled` flag, `timezone`, set of `days`, list of `times`, and `priority`. See [Configuration](../getting-started/configuration.md#schedulesyml) for the exact format.

At any moment, `ScheduleManager` computes the next restart time for every enabled schedule (searching up to 8 days ahead) and starts a single countdown task for whichever one comes first.

## Priority and tie-breaking

The **earliest** upcoming restart time always wins. `priority` is only consulted when two schedules' next restart times fall within **30 seconds** of each other — in that case the schedule with the lower priority number (1 = highest priority) is chosen. Outside that 30-second window, priority has no effect at all.

## Manual restarts

| Command | Effect |
|---|---|
| `/srestart now [reason]` | Starts a 3-second countdown, broadcasting immediately |
| `/srestart in <time> [reason]` | Starts a countdown for the given duration (minimum 10 seconds) |
| `/srestart cancel` | Cancels a pending **manual** restart and re-queues the next scheduled one |

`<time>` accepts combined units, e.g. `30s`, `5m`, `1h30m`, `2h15m30s`. A bare number with no unit is treated as seconds.

A manual restart always takes priority over a scheduled one — starting a manual restart cancels any scheduled task in progress. Only manual restarts can be cancelled with `/srestart cancel`; there is currently no way to cancel a schedule-triggered countdown from a command (you would need to disable the schedule in `schedules.yml` and `/srestart reload`, though that only prevents the *next* occurrence, not one already counting down).

## How a restart actually happens

When the countdown reaches zero:

1. If [grace period](grace-period.md) is enabled and its conditions are met, the restart is deferred and re-checked periodically instead of executing immediately.
2. The restart is written to the restart log (YAML + CSV).
3. A Discord notification fires for manual restarts (scheduled restarts already had their "restarting soon" notification sent by the warning system).
4. If [backups](backups.md) are enabled, the backup runs first; the actual restart happens in the backup's completion callback either way (a failed backup does not block the restart).
5. `general.use-spigot-restart` decides between `spigot().restart()` and `getServer().shutdown()`.

## What's not implemented yet

The in-game GUI (`/srestart gui` → **Create Schedule**) is a placeholder — clicking it just shows "Schedule creation wizard is coming soon!" and does nothing else. To add a new schedule today, edit `schedules.yml` directly and run `/srestart reload`, or edit an *existing* schedule's days/times/timezone/priority through the GUI's schedule editor (that part does work — see [In-Game GUI](in-game-gui.md)).
