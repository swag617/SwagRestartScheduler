# Troubleshooting

## Discord notifications aren't sending

- Confirm **DiscordUtils** is installed and enabled — SwagRestartScheduler has no Discord integration of its own and relies entirely on it.
- Check `discord.enabled` and the per-event `enabled` flags in `config.yml`.
- All three notification types are sent to whichever channel DiscordUtils' own `chat.channel-id` config key points at — there is no per-notification-type routing. Check DiscordUtils' `config.yml`, not this plugin's.
- Check the console around startup for `DiscordUtils integration resolved via ...` — if you instead see `DiscordUtils is enabled as a soft-dependency but its API could not be resolved via reflection` (or a similar message naming `getInstance`/`getDiscordBot`/`sendMessage`), the installed DiscordUtils version doesn't match the class/method signature this plugin looks for, and notifications are permanently disabled until the next server restart.
- See [Discord Notifications](core-features/discord-notifications.md) for the full resolution flow.

## Web config editor changes don't apply to the server

The editor's **Save to Server** button applies changes immediately via `POST /api/config` / `POST /api/schedules` — if changes still aren't taking effect, check the browser console for a failed request and confirm SwagAPI is installed, enabled, and you're signed in (an expired session bounces you to `/login`). See [Web Config Editor](core-features/web-editor.md) for what is and isn't sent to the server (notably: the Discord section is intentionally excluded).

## Web editor URL / `/srestart web` says unavailable

- Confirm **SwagAPI** is installed and enabled — the editor is mounted through SwagAPI's shared web panel and doesn't run its own HTTP server.
- Confirm `web-editor.enabled: true` in `config.yml`.
- Check the console for `SwagAPI IWebService not present — web config editor unavailable.`

## A schedule isn't firing

- Check `/srestart schedules` — if the schedule isn't listed, it failed to parse. Look for a startup warning like `Schedule '<name>' has no valid days — skipped.` or `invalid time '<value>'`.
- Confirm `enabled: true` on the schedule.
- Confirm the `timezone` string is a valid Java `ZoneId` (e.g. `America/New_York`, not `EST`) — invalid values silently fall back to UTC.
- Remember that `priority` only matters when two schedules' next restart times are within 30 seconds of each other; it does not make a schedule "more important" in any other sense.

## Restart keeps getting delayed and never happens

This is the [grace period](core-features/grace-period.md) system. It will force the restart once `grace_period.max_delay_minutes` has elapsed since the restart was originally due, regardless of conditions — if it never restarts at all, grace period is probably not the cause; check the console for other errors instead. If restarts are delaying longer than expected, check whether players with `swagrestart.bypass.grace` are the ones actually staying out of combat/protected worlds (the bypass permission only excludes *that* player from the check, not the whole condition).

## Performance trigger never fires (or fires too often)

- It requires **every** sample in the full `duration_minutes` window to be below `tps_threshold` — a single good tick resets progress on the next check. A short spike back above threshold is enough to prevent it from firing.
- If it seems to fire repeatedly, check `cooldown_minutes` — it resets on every `/srestart reload`, so frequent reloads can defeat the cooldown.
- See [Performance Triggers](core-features/performance-triggers.md).

## Backup fails or is skipped

- A failed backup **never blocks the restart** — check the console for `[Backup]`-prefixed lines to see what happened.
- Folders listed in `backup.include` that don't exist on disk are skipped with a warning rather than failing the whole backup.
- If `maintenance_mode: true` and the **Maintenance** plugin isn't installed, the plugin falls back to `/whitelist on` instead — make sure that's the behavior you want.

## "Create Schedule" in the GUI does nothing

This is a known stub, not a bug — see [Restart Scheduling](core-features/restart-scheduling.md#whats-not-implemented-yet). Add new schedules by editing `schedules.yml` directly and running `/srestart reload`; you can still edit an *existing* schedule's settings from the GUI once it's in the file.

## Still stuck?

Open an issue on [GitHub](https://github.com/swag617/SwagRestartScheduler/issues) or ask in [Discord](https://discord.gg/9rKuThh6yU) with your server version, plugin version, and the relevant console output.
