# Configuration

SwagRestartScheduler reads three files from `plugins/SwagRestartScheduler/`:

| File | Purpose |
|---|---|
| `config.yml` | General settings, warnings, backup, grace period, pre-restart commands, performance triggers, web editor, Discord |
| `schedules.yml` | Named restart schedules |
| `messages.yml` | Every player-facing / command-response message, in MiniMessage format |

Run `/srestart reload` after editing any of them to apply changes without restarting the server.

## `general`

```yaml
general:
  use-spigot-restart: true
  log-prefix: "[SwagRestartScheduler]"
  check-interval-seconds: 10
```

- `use-spigot-restart` — when `true`, restarts call `Bukkit.getServer().spigot().restart()` (requires a wrapper script/launcher that respects the restart exit code). If that call throws, the plugin automatically falls back to `getServer().shutdown()`. When `false`, it goes straight to `shutdown()`.
- `check-interval-seconds` — cosmetic only; it does not affect warning timing precision (warnings fire on wall-clock time, not on this interval).

## `warnings`

```yaml
warnings:
  enabled: true
  intervals:
    - seconds: 1800
      message: "<gold>⚠</gold> <yellow>Server restart in <white>30 minutes</white>!"
      title: null
      subtitle: null
      sound: null
    # ... more entries
  action-bar-threshold: 60
  action-bar-format: "<red><bold>Restarting in {seconds}s"
```

Each entry in `intervals` fires once when the countdown crosses `seconds` remaining. `title`/`subtitle`/`sound` are optional — leave them `null` to send chat-only. `sound` must match a Bukkit `Sound` enum name (e.g. `ENTITY_ENDER_DRAGON_GROWL`); invalid names are skipped with a warning. See [Warnings & Countdown](../core-features/warnings.md) for the full behavior.

## `backup`

```yaml
backup:
  enabled: false
  include: ["world", "world_nether", "world_the_end"]
  destination: "plugins/SwagRestartScheduler/backups"
  max_backups: 5
  compress: true
  maintenance_mode: true
```

Disabled by default. `include` folders are resolved relative to the server's working directory (absolute paths are used as-is); folders that don't exist are skipped with a warning rather than failing the restart. See [Backups](../core-features/backups.md).

## `grace_period`

```yaml
grace_period:
  enabled: false
  max_delay_minutes: 15
  conditions:
    combat: true
    worlds: ["world_boss", "dungeon_*"]
  check_interval_seconds: 5
  message: "<yellow>Restart delayed - players in protected area"
```

Disabled by default. `worlds` supports a simple `*` wildcard. Players with `swagrestart.bypass.grace` are excluded from both conditions. See [Grace Period](../core-features/grace-period.md).

## `pre_restart`

```yaml
pre_restart:
  enabled: true
  commands:
    - delay: 300
      command: "broadcast &cServer restarting in 5 minutes!"
      executor: "console"
```

`delay` is seconds *before* the restart. `executor` only supports `"console"` — there is no per-player executor. See [Pre-Restart Commands](../core-features/pre-restart-commands.md).

## `performance_triggers`

```yaml
performance_triggers:
  enabled: false
  tps_threshold: 15.0
  duration_minutes: 5
  action: "schedule"       # "schedule" | "immediate"
  reason: "Performance degradation detected"
  cooldown_minutes: 60
```

Disabled by default. See [Performance Triggers](../core-features/performance-triggers.md) for how the rolling TPS window and cooldown work.

## `web-editor`

```yaml
web-editor:
  enabled: true
```

Gates whether the config editor registers with SwagAPI's shared web panel at all. Has no effect if SwagAPI isn't installed. See [Web Config Editor](../core-features/web-editor.md).

## `discord`

```yaml
discord:
  enabled: true
  webhook_id: "your_webhook_id"
  notifications:
    scheduled_restart:
      enabled: true
      message: "Server restarting in {time}. Players online: {player_count}"
    manual_restart:
      enabled: true
      message: "Manual restart — {reason}. Initiated by: {initiator}"
    server_online:
      enabled: true
      message: "Server has restarted successfully!"
```

`webhook_id` refers to a webhook configured *inside the separate DiscordUtils plugin* — SwagRestartScheduler does not manage Discord webhooks itself. If `webhook_id` is left at the placeholder value or blank, or DiscordUtils isn't installed, notifications are silently skipped. See [Discord Notifications](../core-features/discord-notifications.md).

## `schedules.yml`

```yaml
schedules:
  weekday:
    enabled: true
    timezone: "America/New_York"
    days: [MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY]
    times: ["3 AM", "3 PM"]
    priority: 1

  weekend:
    enabled: true
    timezone: "America/New_York"
    days: [SATURDAY, SUNDAY]
    times: ["5 AM"]
    priority: 1
```

- `timezone` — any valid Java `ZoneId` string (`UTC`, `America/New_York`, `Europe/London`, ...). Invalid values fall back to `UTC` with a console warning.
- `times` — accepts `"3 AM"`, `"3:30 PM"`, or 24-hour `"15:00"` / `"03:00"`. Invalid entries are skipped with a warning; a schedule with zero valid times is skipped entirely.
- `priority` — lower number = higher priority (1 = highest). Only used to break a tie when two schedules' next restart times land within 30 seconds of each other — otherwise the genuinely earlier restart always wins regardless of priority.

See [Restart Scheduling](../core-features/restart-scheduling.md) for the full scheduling algorithm.
