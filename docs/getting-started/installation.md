# Installation

## 1. Requirements

- **Paper 1.20.4** or newer — the plugin is built against `paper-api 1.20.4-R0.1-SNAPSHOT` with `api-version: 1.20` declared in `plugin.yml`
- **Java 21**

## 2. Install

Drop `SwagRestartScheduler.jar` into your server's `plugins/` folder. No other plugin is required — scheduling, warnings, grace period, pre-restart commands, backups, and logging all work standalone.

## 3. Optional integrations

Install any of these *before* starting the server if you want the matching feature. All four are soft-dependencies: SwagRestartScheduler starts fine without them and simply disables the related feature (with a console warning).

| Plugin | Enables |
|---|---|
| DiscordUtils | [Discord notifications](../core-features/discord-notifications.md) |
| PlaceholderAPI | Placeholder substitution in [pre-restart commands](../core-features/pre-restart-commands.md) |
| CombatLogX | The combat condition in the [grace period](../core-features/grace-period.md) system |
| SwagAPI | The [web config editor](../core-features/web-editor.md) |

> `plugin.yml` also lists `WorldGuard` and `Vault` as soft-dependencies, but neither is referenced anywhere in the plugin's code. There is currently no WorldGuard or Vault integration — installing them has no effect.

## 4. Start the server

Starting the server for the first time generates:

- `plugins/SwagRestartScheduler/config.yml`
- `plugins/SwagRestartScheduler/schedules.yml`
- `plugins/SwagRestartScheduler/messages.yml`
- `plugins/SwagRestartScheduler/web/config-editor.html` — only if `web-editor.enabled` is `true` and the file doesn't already exist

Stop the server and review [Configuration](configuration.md) before your first live restart, especially the default `schedules.yml` entries (3 AM / 3 PM weekdays, 5 AM weekends, `America/New_York`) — you almost certainly want to change these.

## 5. Verify

Console should log `SwagRestartScheduler enabled in <n>ms.` on startup, followed by a `Loaded schedule '<name>': ...` line for each schedule in `schedules.yml`. Run `/srestart status` in-game or from console to confirm the next restart time was picked up correctly.
