# SwagRestartScheduler - Automated Paper Restart Scheduling

Automated scheduled and manual server restarts for Paper, with a full countdown/warning system, TPS-based performance triggers, pre-restart backups, crash-loop protection, and Discord notifications. Restart schedules, warnings, grace-period rules, and backups can be edited live in-game through a GUI, or offline through YAML files.

## Features

- **Named restart schedules** — any number of schedules in `schedules.yml`, each with its own days, times, timezone, and priority; the earliest upcoming restart across all schedules wins
- **Manual restarts** — `/srestart now [reason]` and `/srestart in <time> [reason]`, cancellable with `/srestart cancel`
- **Warning broadcasts** — configurable countdown thresholds with chat messages, titles/subtitles, sounds, an action-bar countdown, and a final-moments boss bar (hard-capped at 10 seconds)
- **Grace period** — delay a restart while players are in combat (via CombatLogX), in protected worlds, or while at least a configured number of players are online
- **Pre-restart commands** — run console commands at configured offsets before the restart, with optional PlaceholderAPI substitution
- **Pre-restart backups** — zips (or copies) configured world folders before restarting, with maintenance-mode/whitelist lockout and automatic pruning of old backups
- **TPS-based performance triggers** — automatically schedule (or immediately force) a restart when average TPS stays below a threshold for a sustained period
- **Crash-loop safe mode** — detects the JVM dying without a clean shutdown and temporarily suppresses scheduled/performance-triggered restarts if it happens repeatedly, so a misbehaving server isn't restarted back into whatever is crashing it
- **Discord notifications** — scheduled restart, manual restart, server-online, and crash-loop alert messages published through SwagAPI's shared event bus for the DiscordUtils plugin to relay
- **In-game GUI** — `/srestart gui` for browsing/editing schedules, toggling warnings and backup settings, and viewing recent restart log entries, without touching YAML
- **Web config editor** — a browser-based form for building `config.yml` / `schedules.yml`, served through SwagAPI's shared web panel
- **Restart logging** — every restart is appended to both a YAML log and a CSV log for external analysis

## Requirements

| Dependency | Required |
|---|---|
| Paper 1.20.4+ | Yes |
| Java 21 | Yes |
| SwagAPI | **Yes** — hard dependency (`depend` in `plugin.yml`); the plugin will not enable without it |
| DiscordUtils | No — only needed for Discord notifications |
| PlaceholderAPI | No — only used to expand placeholders in pre-restart console commands |
| CombatLogX | No — only needed for the combat grace-period condition |

> `plugin.yml` also lists `WorldGuard` and `Vault` as soft-dependencies, but neither is referenced anywhere in the current code — there is no WorldGuard or Vault integration despite the declaration.

## Building

### Prerequisites
- Java JDK 21
- Maven 3.8+
- A locally built/installed [SwagAPI](https://github.com/swag617/SwagAPI) jar (compile-time `provided` dependency)

### Build Commands

```bash
# Clone the repository
git clone https://github.com/swag617/SwagRestartScheduler.git
cd SwagRestartScheduler

# Clean and package
mvn clean package

# Output JAR will be in: target/SwagRestartScheduler-2.3.0.jar
```

Install SwagAPI on your server first, then drop `SwagRestartScheduler.jar` into `plugins/`. See the docs for the full installation and configuration walkthrough.

## Project Structure

```
SwagRestartScheduler/
├── pom.xml                                             # Maven build configuration
├── src/main/
│   ├── java/com/swag617/restartsched/
│   │   ├── SwagRestartScheduler.java                   # Main plugin class
│   │   ├── automation/                                 # Pre-restart command execution
│   │   ├── backup/                                     # Pre-restart world backups
│   │   ├── command/                                    # /srestart command handler
│   │   ├── config/                                     # Config loading/reload
│   │   ├── crashloop/                                  # Crash-loop detection & safe mode
│   │   ├── discord/                                    # Discord notifications (via SwagAPI event bus)
│   │   ├── grace/                                      # Grace-period condition checks
│   │   ├── gui/                                        # In-game GUI (schedules, settings, backups)
│   │   ├── logging/                                    # Restart log (YAML + CSV)
│   │   ├── performance/                                # TPS monitor & performance triggers
│   │   ├── schedule/                                   # Named schedule management
│   │   ├── task/                                       # Countdown/restart execution task
│   │   ├── warning/                                    # Warning broadcasts, action bar, boss bar
│   │   └── web/                                        # Web config editor (via SwagAPI web service)
│   └── resources/
│       ├── plugin.yml                                  # Plugin metadata
│       ├── config.yml                                  # Main configuration
│       ├── schedules.yml                               # Default restart schedules
│       └── messages.yml                                # Player-facing messages
└── docs/                                                # Docsify documentation site
```

## Documentation

Full documentation — installation, configuration, every core feature, and admin commands — is published at:

**https://swag617.github.io/SwagRestartScheduler/**

## Downloads

Prebuilt releases are published on GitHub:

**https://github.com/swag617/SwagRestartScheduler/releases**

## License

Proprietary software developed for the Swag617 plugin ecosystem. All rights reserved.
