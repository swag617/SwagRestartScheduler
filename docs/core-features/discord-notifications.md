# Discord Notifications

> **This is a soft-dependency shim, not a built-in Discord client.** SwagRestartScheduler has no webhook/HTTP code of its own for Discord — it locates the separate **DiscordUtils** plugin via reflection at runtime and calls `DiscordUtils.getInstance().getDiscordBot().sendMessage(String)`. If DiscordUtils isn't installed, every notification call is silently a no-op.

## Requirements

- The **DiscordUtils** plugin (package `com.swag.discordutils`) installed, enabled, and with its own bot connected (`bot-token` configured)
- DiscordUtils' own `chat.channel-id` config key pointing at a real channel — **all three** notification types below are sent there. DiscordUtils has no per-webhook or per-notification-type channel routing, so there is no way to send scheduled-restart pings to one channel and manual-restart pings to another.

## Resolution

On first use, `DiscordNotifier` reflectively resolves `com.swag.discordutils.DiscordUtils#getInstance()`, then `#getDiscordBot()`, then looks up `sendMessage(String)` on the returned `DiscordBot` object. If any step fails — DiscordUtils isn't installed, its bot never connected (`getDiscordBot()` can return null), or a future DiscordUtils release changes this API shape — notifications are permanently disabled for the rest of the server's uptime and a warning is logged once explaining which step failed. There's no periodic retry and `/srestart reload` does **not** re-attempt resolution (it only reloads this plugin's own config) — a full server/plugin restart is required.

## Events sent

| Event | Config key | Placeholders |
|---|---|---|
| Scheduled restart warning fires | `discord.notifications.scheduled_restart` | `{time}`, `{player_count}` |
| Manual restart initiated | `discord.notifications.manual_restart` | `{reason}`, `{initiator}` |
| Server finished starting | `discord.notifications.server_online` | none |

Each has its own `enabled` flag and `message` template, plus the global `discord.enabled` switch.

```yaml
discord:
  enabled: true
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

All sends happen on an async thread via `Bukkit.getScheduler().runTaskAsynchronously`, so a slow or failing Discord API call never blocks the main thread — including the one fired right as the server is shutting down.
