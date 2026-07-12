# Grace Period

Disabled by default (`grace_period.enabled: false`). When enabled, the plugin can delay a restart past its scheduled/countdown time while certain conditions hold, instead of restarting on the dot.

## Conditions

| Condition | Requires | Behavior |
|---|---|---|
| `combat` | CombatLogX installed | Delays while any online player (without the bypass permission) is currently tagged in combat |
| `worlds` | — | Delays while any online player (without the bypass permission) is in a world matching one of the configured name patterns |

`worlds` entries support a single `*` wildcard, e.g. `dungeon_*` matches `dungeon_1`, `dungeon_boss`, etc. If CombatLogX is not installed, the `combat` condition is silently ignored (treated as never true) rather than erroring.

## Bypass

Players with `swagrestart.bypass.grace` are excluded from both checks — their combat status and current world never trigger a delay.

## Maximum delay

```yaml
grace_period:
  max_delay_minutes: 15
  check_interval_seconds: 5
```

Once the countdown hits zero, the plugin starts tracking how long it has been delaying. If conditions are still blocking the restart after `max_delay_minutes` have elapsed since the *original* due time, the restart is forced regardless of combat/world state. While delayed, conditions are re-checked every `check_interval_seconds`, and the configured `message` is broadcast each time a delay is (re-)applied.

## What this does *not* do

There's no per-schedule or per-restart-type override — grace period is a single global on/off with one set of conditions applied to every restart, scheduled or manual.
