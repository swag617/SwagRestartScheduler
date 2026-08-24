# Configuration

SwagRestartScheduler reads three files from `plugins/SwagRestartScheduler/`:

| File | Purpose |
|---|---|
| `config.yml` | General settings, warnings, boss bar, backup, grace period, pre-restart commands, performance triggers, event bus, crash-loop safe mode, web editor, Discord |
| `schedules.yml` | Named restart schedules |
| `messages.yml` | Every player-facing / command-response message, in MiniMessage format |

Run `/srestart reload` after editing any of them to apply changes without restarting the server.

## `general`

```yaml
general:
  use-spigot-restart: true
  log-prefix: "[SwagRestartScheduler]"
```

- `use-spigot-restart` — when `true`, restarts call `Bukkit.getServer().spigot().restart()` (requires a wrapper script/launcher that respects the restart exit code). If that call throws, the plugin automatically falls back to `getServer().shutdown()`. When `false`, it goes straight to `shutdown()`.

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

## `boss_bar`

```yaml
boss_bar:
  enabled: true
  seconds-threshold: 10
  color: "RED"
  overlay: "PROGRESS"
  title-format: "<red><bold>{reason} — restarting in {seconds}s"
```

A final-moments-only countdown boss bar, separate from the action bar above. `seconds-threshold` is hard-capped at 10 in code no matter what it's set to here — a longer bar was judged too naggy during review. `color`/`overlay` must match Adventure's `BossBar.Color`/`BossBar.Overlay` enum names; invalid values fall back to `RED`/`PROGRESS` with a warning. See [Warnings & Countdown](../core-features/warnings.md#boss-bar-countdown).

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
    min-players-online: 0
  check_interval_seconds: 5
  message: "<yellow>Restart delayed - players in protected area"
```

Disabled by default. `worlds` supports a simple `*` wildcard. `min-players-online` delays the restart while at least that many players are online (`0` disables the check). Players with `swagrestart.bypass.grace` are excluded from the `combat` and `worlds` conditions. See [Grace Period](../core-features/grace-period.md).

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

## `event_bus`

```yaml
event_bus:
  enabled: true
  default-eta-seconds: 3
  backup-eta-seconds: 15
```

Publishes a `server.restart.pending` event on SwagAPI's shared event bus right before a restart executes, for any other Swag617 plugin to react to. No-ops if SwagAPI's event bus isn't registered. See [Discord Notifications](../core-features/discord-notifications.md#cross-plugin-restart-pending-event).

## `crash-loop-safe-mode`

```yaml
crash-loop-safe-mode:
  enabled: true
  unclean-shutdown-threshold: 2
  detection-window-minutes: 15
  safe-mode-cooldown-minutes: 30
  discord-message: "⚠ CRASH LOOP DETECTED: {crash_count} unclean shutdown(s) within {window}m. Scheduled/performance-triggered restarts suppressed for {cooldown}m."
```

Detects the JVM dying without a clean `onDisable()` and temporarily suppresses scheduled/performance-triggered restarts if it happens repeatedly in a short window. Manual restarts are never suppressed. See [Crash-Loop Safe Mode](../core-features/crash-loop-safe-mode.md).

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
  webhook-name: "restart"
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

`webhook-name` refers to a named entry under `webhooks:` configured *inside the separate DiscordUtils plugin* — SwagRestartScheduler does not manage Discord webhooks itself, it publishes messages on SwagAPI's shared event bus for DiscordUtils to relay. If DiscordUtils isn't installed or has no matching webhook entry, notifications are silently dropped. See [Discord Notifications](../core-features/discord-notifications.md).

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
