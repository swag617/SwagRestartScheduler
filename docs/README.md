# 🔄 SwagRestartScheduler

> Automated Paper server restart scheduling with warnings, grace periods, backups, and Discord notifications

SwagRestartScheduler runs scheduled and manual server restarts on Paper, with a full countdown/warning system, an optional pre-restart backup step, TPS-based performance triggers, and Discord notifications relayed through the DiscordUtils plugin. Restart schedules, warnings, grace-period rules, and backups can be edited live in-game through a GUI, or offline through YAML files.

> **Project status:** SwagRestartScheduler is under active development. Restart scheduling, warnings, grace period, pre-restart commands, backups, performance triggers, Discord notifications, the in-game GUI (including schedule creation/deletion), and the web config editor's live save/load are all implemented and working. See [Restart Scheduling](core-features/restart-scheduling.md#whats-not-implemented-yet) for the one remaining known gap (cancelling an in-progress *scheduled* countdown from a command).

---

## Features

- **Named restart schedules** — any number of schedules in `schedules.yml`, each with its own days, times, timezone, and priority; the earliest upcoming restart across all schedules wins, with priority used only to break near-simultaneous ties
- **Manual restarts** — `/srestart now [reason]` and `/srestart in <time> [reason]`, cancellable with `/srestart cancel`
- **Warning broadcasts** — configurable countdown thresholds with chat messages, titles/subtitles, sounds, an action-bar countdown, and a final-moments boss bar
- **Grace period** — delay a restart while players are in combat (via CombatLogX), in protected worlds, or while at least a configured number of players are online, up to a configurable maximum delay
- **Pre-restart commands** — run console commands at configured offsets before the restart, with optional PlaceholderAPI substitution
- **Pre-restart backups** — zips (or copies) configured world folders before restarting, with maintenance-mode/whitelist lockout and automatic pruning of old backups
- **TPS-based performance triggers** — automatically schedule (or immediately force) a restart when average TPS stays below a threshold for a sustained period
- **Crash-loop safe mode** — detects the JVM dying without a clean shutdown and temporarily suppresses scheduled/performance-triggered restarts if it happens repeatedly, so a misbehaving server isn't restarted back into whatever is crashing it
- **Discord notifications** — scheduled restart, manual restart, server-online, and crash-loop alert messages published through SwagAPI's shared event bus for the DiscordUtils plugin to relay
- **In-game GUI** — `/srestart gui` for browsing/editing schedules, toggling warnings and backup settings, and viewing recent restart log entries, without touching YAML
- **Web config editor** — a browser-based form for building `config.yml` / `schedules.yml`, served through SwagAPI's shared web panel
- **Restart logging** — every restart is appended to both a YAML log and a CSV log for external analysis

---

## Quick Links

| | |
|---|---|
| [Installation](getting-started/installation.md) | Get SwagRestartScheduler running on your server |
| [Configuration](getting-started/configuration.md) | All `config.yml` and `schedules.yml` options explained |
| [Commands](admin-commands.md) | Full `/srestart` command reference |
| [Permissions](permissions.md) | Permission nodes |
| [Troubleshooting](troubleshooting.md) | Common issues |

---

## Requirements

| Dependency | Required |
|---|---|
| Paper 1.20.4+ | Yes |
| Java 21 | Yes |
| SwagAPI | **Yes** — hard dependency (`depend` in `plugin.yml`); the plugin will not enable without it |
| DiscordUtils | No — only needed for Discord notifications |
| PlaceholderAPI | No — only used to expand placeholders in pre-restart console commands |
| CombatLogX | No — only needed for the combat grace-period condition |

> **Note:** `plugin.yml` also lists `WorldGuard` and `Vault` as soft-dependencies, but neither is referenced anywhere in the current code — there is no WorldGuard or Vault integration yet, despite the declaration.
