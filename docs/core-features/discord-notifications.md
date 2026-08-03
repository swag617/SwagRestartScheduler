# Discord Notifications

> **This is a relay shim, not a built-in Discord client.** SwagRestartScheduler has no webhook/HTTP code of its own for Discord — it publishes messages on **SwagAPI's shared event bus** (`IEventBusService`) to the `discordutils:notify` channel, and the separate **DiscordUtils** plugin picks them up and posts them. There is zero compile-time or reflection-based coupling to DiscordUtils itself.

> As of 2.2.0 this replaced an earlier reflection-based integration (`DiscordUtils.getInstance().getDiscordBot().sendMessage(String)`) that had no per-notification-type channel routing — every message went wherever DiscordUtils' `chat.channel-id` pointed. The event-bus payload below fixes that: restart notifications get their own named webhook entry in DiscordUtils, independent of its chat relay.

## Requirements

- **SwagAPI** installed and enabled, with its `IEventBusService` registered (always true once SwagAPI is running — SwagRestartScheduler has a hard dependency on SwagAPI, see [Installation](../getting-started/installation.md))
- The **DiscordUtils** plugin installed, enabled, subscribed to the `discordutils:notify` channel, and configured with a `webhooks.<webhook-name>` entry matching `discord.webhook-name` below

## How it's sent

`DiscordNotifier` looks up `IEventBusService` from Bukkit's `ServicesManager` and calls `publish(...)` with a `SwagCrossPluginMessageEvent` on channel `discordutils:notify`, carrying:

```json
{
  "webhook":  "<discord.webhook-name>",
  "content":  "<formatted message>",
  "username": "Server Status"
}
```

If SwagAPI's event bus isn't registered for some reason, the notification is dropped with a one-time warning — it never blocks or throws. Because `IEventBusService#publish` is a synchronous, in-process call (all the actual network I/O happens inside DiscordUtils), no async dispatch is needed on SwagRestartScheduler's side.

## Events sent

| Event | Config key | Placeholders |
|---|---|---|
| Scheduled restart warning fires | `discord.notifications.scheduled_restart` | `{time}`, `{player_count}` |
| Manual restart initiated | `discord.notifications.manual_restart` | `{reason}`, `{initiator}` |
| Server finished starting | `discord.notifications.server_online` | none |
| Crash loop detected | `crash-loop-safe-mode.discord-message` | `{crash_count}`, `{window}`, `{cooldown}` |

The first three each have their own `enabled` flag and `message` template, plus the global `discord.enabled` switch. The crash-loop alert is **not** individually gated by a `discord.notifications.*.enabled` toggle — a crash loop is always alert-worthy — but it still respects `discord.enabled`. See [Crash-Loop Safe Mode](crash-loop-safe-mode.md) for when that alert fires.

```yaml
discord:
  enabled: true
  # Which named webhook (from DiscordUtils' config.yml "webhooks:" section) these
  # notifications route to.
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

Notice there is no dedicated "restarting now" message for scheduled restarts — the "scheduled restart" notification is sent when the first configured warning threshold fires (see [Warnings & Countdown](warnings.md)), not at the moment the server actually goes down, to avoid a duplicate ping right before shutdown.

## Cross-plugin restart-pending event

Separately from Discord, `RestartTask` also publishes a `server.restart.pending` event on the same `IEventBusService` (channel `server.restart.pending`, not `discordutils:notify`) once a restart is confirmed and about to execute — for any other Swag617 plugin that wants to react before the server actually goes down, not just DiscordUtils. It carries `etaSeconds` (a best-effort estimate, including backup time if a backup will run), `initiator`, and `scheduleId` (`null` for manual/performance-triggered restarts). Gated by:

```yaml
event_bus:
  enabled: true
  # Fallback ETA (seconds) used when no pre-restart backup will run.
  default-eta-seconds: 3
  # Added to the ETA when a pre-restart backup will run — backups add real delay
  # between this event firing and the server actually stopping.
  backup-eta-seconds: 15
```

Like the Discord path, this silently no-ops if SwagAPI's event bus isn't registered, and a publish failure never blocks the actual restart.
