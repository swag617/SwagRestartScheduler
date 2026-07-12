# Performance Triggers

Disabled by default (`performance_triggers.enabled: false`). Monitors server TPS and can automatically trigger a restart when performance has been consistently bad.

## How TPS is sampled

`TpsMonitor` polls `Bukkit.getServer().getTPS()[0]` (Paper's 1-minute rolling average) once per second and keeps a circular buffer sized to `duration_minutes * 60` samples.

## Trigger condition

```yaml
performance_triggers:
  enabled: false
  tps_threshold: 15.0
  duration_minutes: 5
  action: "schedule"       # "schedule" | "immediate"
  reason: "Performance degradation detected"
  cooldown_minutes: 60
```

The trigger only fires once the buffer is **completely full** (i.e. `duration_minutes` worth of samples have been collected) **and every single sample** in that window is below `tps_threshold`. A single good tick anywhere in the window resets the "consistently below" check on the next evaluation.

## Actions

- `"schedule"` — starts a manual restart task 5 minutes out, so warning broadcasts have time to fire
- `"immediate"` — starts a manual restart task with a 3-second countdown, same as `/srestart now`

Either way, the trigger is logged (console + restart log, with the average TPS recorded), a warning is broadcast to all players, and both the evaluation task and the TPS monitor are stopped — monitoring does not resume until the next `/srestart reload` or server restart.

## Cooldown

After firing, the trigger will not fire again for `cooldown_minutes`. The cooldown timer resets on `/srestart reload`.
