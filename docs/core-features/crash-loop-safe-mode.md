# Crash-Loop Safe Mode

Detects the JVM dying without ever reaching `onDisable()` (a crash, a kill, or a host reboot) and, once detected repeatedly, temporarily suppresses **automated** restart triggers so a misbehaving server isn't immediately kicked back into whatever condition is crashing it.

## How an "unclean shutdown" is detected

At the very top of `onEnable()` — before any other manager initializes — the plugin writes a marker file, `shutdown.lock`, to its data folder. At the very top of a clean `onDisable()`, that marker is deleted. If the marker is **already present** the next time the plugin starts up, the previous JVM never reached `onDisable()`: it crashed, was killed, or the host rebooted without a graceful stop.

## Rolling crash window

Each detected unclean shutdown appends a timestamp to `crash-log.txt` in the plugin's data folder. Only entries within `crash-loop-safe-mode.detection-window-minutes` are kept — older ones are dropped the next time the file is rewritten.

## Safe mode

If `crash-loop-safe-mode.unclean-shutdown-threshold` unclean shutdowns are recorded within the detection window, safe mode activates for `crash-loop-safe-mode.safe-mode-cooldown-minutes`, starting from the startup that tripped it. While active:

- Performance-triggered restarts (see [Performance Triggers](performance-triggers.md)) are suppressed
- A new **scheduled** restart countdown will not start

**Manual restarts** (`/srestart now`, `/srestart in`) are never suppressed — an admin who explicitly asks for a restart always gets one.

If [Discord notifications](discord-notifications.md) are enabled, entering safe mode also sends a crash-loop alert using the `crash-loop-safe-mode.discord-message` template (`{crash_count}`, `{window}`, `{cooldown}` placeholders). This alert is not gated by a per-notification `enabled` toggle the way the other Discord messages are — only by the top-level `discord.enabled` switch.

## Configuration

```yaml
crash-loop-safe-mode:
  enabled: true
  # How many unclean shutdowns within the detection window trigger safe mode.
  unclean-shutdown-threshold: 2
  # Rolling window (minutes) in which unclean shutdowns are counted.
  detection-window-minutes: 15
  # How long (minutes) scheduled/performance-triggered restarts are suppressed once triggered.
  safe-mode-cooldown-minutes: 30
  # Discord alert message — {crash_count}, {window}, {cooldown} placeholders.
  discord-message: "⚠ CRASH LOOP DETECTED: {crash_count} unclean shutdown(s) within {window}m. Scheduled/performance-triggered restarts suppressed for {cooldown}m."
```

`/srestart reload` reloads the threshold/window/cooldown values, but does not clear an already-active safe-mode window or the on-disk crash log.
