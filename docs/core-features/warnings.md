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

## Toggling at runtime

The in-game GUI's Settings page (`/srestart gui` → Settings) has a one-click toggle for `warnings.enabled` that writes straight to `config.yml` and reloads the warning manager — no need to hand-edit YAML for a quick on/off.
