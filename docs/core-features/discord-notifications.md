# Discord Notifications

> **This is a soft-dependency shim, not a built-in Discord client.** SwagRestartScheduler has no webhook/HTTP code of its own for Discord — it locates the separate **DiscordUtils** plugin via reflection at runtime and calls its `sendWebhookMessage(String webhookId, String message)` method. If DiscordUtils isn't installed, every notification call is silently a no-op.

## Requirements

- The [DiscordUtils](https://www.spigotmc.org/) plugin installed and enabled
- A webhook already configured *inside DiscordUtils* — `discord.webhook_id` in `config.yml` just references that webhook by ID, it doesn't create one

## Resolution

On first use, `DiscordNotifier` tries a short list of candidate class names (`me.swagbot.discordutils.DiscordUtils` and a couple of fallbacks) looking for a `sendWebhookMessage(String, String)` method, then tries to obtain an instance via a static `getInstance()` or the plugin's own `getInstance()`/`getAPI()`. If none of that resolves, notifications are permanently disabled for the rest of the server's uptime and a warning is logged once — there's no periodic retry.

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

Notice there is no dedicated "restarting now" message for scheduled restarts — the "scheduled restart" notification is sent when the first configured warning threshold fires (see [Warnings & Countdown](warnings.md)), not at the moment the server actually goes down, to avoid a duplicate ping right before shutdown.

All sends happen on an async thread via `Bukkit.getScheduler().runTaskAsynchronously`, so a slow or failing webhook call never blocks the main thread — including the one fired right as the server is shutting down.
