# In-Game GUI

Open with `/srestart gui` (requires `swagrestart.gui`). Unlike the [web editor](web-editor.md), the in-game GUI writes directly to `config.yml` / `schedules.yml` on disk and reloads the relevant manager immediately — changes take effect right away.

## Main Menu

27-slot menu with five entries:

| Item | Action |
|---|---|
| View Schedules | Opens a paginated list of schedules from `schedules.yml` (45 per page, with Previous/Next Page controls) |
| Create Schedule | Prompts for a name in chat, then opens the schedule editor seeded with defaults — nothing is written until you click **Save & Close** |
| Settings | Toggle `warnings.enabled`, reload all config, view next-restart info |
| Backup Settings | Toggle backup on/off, maintenance mode, compression, and set `max_backups` |
| Restart Logs | Loads the last 10 entries from the restart log into the item's tooltip |

## Schedule editor

Clicking an existing schedule in the list opens a full editor: toggle `enabled`, edit restart times, edit active days, and change the timezone, all in a 54-slot GUI. Changes are held in a working copy and only written to `schedules.yml` when you click **Save & Close** — closing the inventory any other way discards them.

From the schedule list, **shift-left-click** a schedule to permanently delete it — you'll be prompted to type `CONFIRM` in chat first (typing anything else, or nothing, cancels).

## Settings

- **Warning System** toggle — flips `warnings.enabled` and reloads `WarningManager`
- **Reload Config** — equivalent to `/srestart reload`
- **Next Restart Info** — read-only summary of the active or next-scheduled restart; click to refresh

## Backup Settings

Toggles `backup.enabled`, `backup.maintenance_mode`, and `backup.compress` in place, and lets you set `backup.max_backups` through a chat-input prompt (type a number in chat after clicking). The `include` folder list and `destination` path are not editable from the GUI — those still require hand-editing `config.yml`.

## Restart Logs

This is a lore-tooltip snapshot, not a scrollable log viewer — clicking "Restart Logs" reads the last 10 entries from `logs/restart-log.yml` and rewrites the item's hover text with them (timestamp, type, initiator, reason). For anything beyond the last 10 entries, or for machine-readable output, use `/srestart logs export` to get the path to the full CSV log instead.
