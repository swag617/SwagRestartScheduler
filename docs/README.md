# 🔄 SwagRestartScheduler

> Automated Paper server restart scheduling with warnings, grace periods, backups, and Discord notifications

SwagRestartScheduler runs scheduled and manual server restarts on Paper, with a full countdown/warning system, an optional pre-restart backup step, TPS-based performance triggers, and Discord notifications relayed through the DiscordUtils plugin. Restart schedules, warnings, grace-period rules, and backups can be edited live in-game through a GUI, or offline through YAML files.

> **Project status:** SwagRestartScheduler is under active development. Restart scheduling, warnings, grace period, pre-restart commands, backups, performance triggers, Discord notifications, and the in-game GUI are all implemented and working. A couple of pieces are explicitly unfinished — see the callouts on [Restart Scheduling](core-features/restart-scheduling.md) (schedule-creation wizard) and [Web Config Editor](core-features/web-editor.md) (no server-side save yet).

---

## Features

- **Named restart schedules** — any number of schedules in `schedules.yml`, each with its own days, times, timezone, and priority; the earliest upcoming restart across all schedules wins, with priority used only to break near-simultaneous ties
- **Manual restarts** — `/srestart now [reason]` and `/srestart in <time> [reason]`, cancellable with `/srestart cancel`
- **Warning broadcasts** — configurable countdown thresholds with chat messages, titles/subtitles, sounds, and an action-bar countdown
- **Grace period** — delay a restart while players are in combat (via CombatLogX) or in protected worlds, up to a configurable maximum delay
- **Pre-restart commands** — run console commands at configured offsets before the restart, with optional PlaceholderAPI substitution
- **Pre-restart backups** — zips (or copies) configured world folders before restarting, with maintenance-mode/whitelist lockout and automatic pruning of old backups
- **TPS-based performance triggers** — automatically schedule (or immediately force) a restart when average TPS stays below a threshold for a sustained period
- **Discord notifications** — scheduled restart, manual restart, and server-online messages sent through the DiscordUtils plugin's webhook API
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
| DiscordUtils | No — only needed for Discord notifications |
| PlaceholderAPI | No — only used to expand placeholders in pre-restart console commands |
| CombatLogX | No — only needed for the combat grace-period condition |
| SwagAPI | No — only needed for the web config editor |

> **Note:** `plugin.yml` also lists `WorldGuard` and `Vault` as soft-dependencies, but neither is referenced anywhere in the current code — there is no WorldGuard or Vault integration yet, despite the declaration.
