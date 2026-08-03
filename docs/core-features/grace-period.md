# Grace Period

Disabled by default (`grace_period.enabled: false`). When enabled, the plugin can delay a restart past its scheduled/countdown time while certain conditions hold, instead of restarting on the dot.

## Conditions

| Condition | Requires | Behavior |
|---|---|---|
| `combat` | CombatLogX installed | Delays while any online player (without the bypass permission) is currently tagged in combat |
| `worlds` | — | Delays while any online player (without the bypass permission) is in a world matching one of the configured name patterns |
| `min-players-online` | — | Delays while the server's online player count is at least this many (`0` disables the check) |

`worlds` entries support a single `*` wildcard, e.g. `dungeon_*` matches `dungeon_1`, `dungeon_boss`, etc. If CombatLogX is not installed, the `combat` condition is silently ignored (treated as never true) rather than erroring. `min-players-online` is a straight `Bukkit.getOnlinePlayers().size()` check — it is **not** subject to the bypass permission described below (that only affects the per-player `combat`/`worlds` checks).

```yaml
grace_period:
  conditions:
    combat: true
    worlds: ["world_boss", "dungeon_*"]
    min-players-online: 0
```

## Bypass

Players with `swagrestart.bypass.grace` are excluded from the `combat` and `worlds` checks — their combat status and current world never trigger a delay. This does not affect `min-players-online`, which counts all online players regardless of permission.

## Maximum delay

```yaml
grace_period:
  max_delay_minutes: 15
  check_interval_seconds: 5
```

Once the countdown hits zero, the plugin starts tracking how long it has been delaying. If conditions are still blocking the restart after `max_delay_minutes` have elapsed since the *original* due time, the restart is forced regardless of combat/world state. While delayed, conditions are re-checked every `check_interval_seconds`, and the configured `message` is broadcast each time a delay is (re-)applied.

## What this does *not* do

There's no per-schedule or per-restart-type override — grace period is a single global on/off with one set of conditions applied to every restart, scheduled or manual.
