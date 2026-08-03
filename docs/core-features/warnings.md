# Warnings & Countdown

Configured under `warnings` in `config.yml`. Each entry in `warnings.intervals` is checked once per second against the remaining countdown; when the countdown crosses a threshold, that entry fires exactly once (thresholds larger than the total countdown duration are pre-marked as already-fired, so a `/srestart in 1m` won't try to send a 30-minute warning).

## What an entry can do

```yaml
- seconds: 60
  message: "<red>⚠</red> <yellow>Server restart in <white>1 minute</white>! Save your progress!"
  title: "<red><bold>RESTARTING SOON"
  subtitle: "<yellow>1 minute remaining"
  sound: "ENTITY_ENDER_DRAGON_GROWL"
```

- `message` — broadcast in chat to every online player (MiniMessage formatting)
- `title` / `subtitle` — shown as an Adventure title/subtitle (0.5s fade in, 3s stay, 0.5s fade out) if either is set; leave both `null` to skip
- `sound` — must match a Bukkit `Sound` enum constant name; unrecognized names are skipped with a console warning

## Action bar countdown

```yaml
action-bar-threshold: 60
action-bar-format: "<red><bold>Restarting in {seconds}s"
```

Once the countdown reaches `action-bar-threshold` seconds remaining, every online player sees a live action-bar countdown updated once per second, using `{seconds}` as the placeholder. Set the threshold to `0` to disable it.

## Boss bar countdown

```yaml
boss_bar:
  enabled: true
  seconds-threshold: 10
  color: "RED"
  overlay: "PROGRESS"
  title-format: "<red><bold>{reason} — restarting in {seconds}s"
```

A separate, final-moments-only countdown shown as a boss bar to every online player, with `{reason}` and `{seconds}` placeholders in the title and progress computed as `secondsRemaining / seconds-threshold`. `seconds-threshold` is **hard-capped at 10 in code** regardless of what it's set to in config — a 30-60 second boss bar was judged too annoying during review, so this is intentionally a short, final-countdown-only display, never a long naggy one. `color` and `overlay` must match Adventure's `BossBar.Color` / `BossBar.Overlay` enum constant names (e.g. `RED`, `BLUE`, `PINK` / `PROGRESS`, `NOTCHED_10`); unrecognized values fall back to `RED` / `PROGRESS` with a console warning. The bar is automatically hidden when the countdown is cancelled or reaches zero.

## Toggling at runtime

The in-game GUI's Settings page (`/srestart gui` → Settings) has a one-click toggle for `warnings.enabled` that writes straight to `config.yml` and reloads the warning manager — no need to hand-edit YAML for a quick on/off.
