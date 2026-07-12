# Backups

Disabled by default (`backup.enabled: false`). When enabled, `BackupManager` runs automatically right before every restart — scheduled, manual, or performance-triggered.

## Sequence

1. If `maintenance_mode` is `true`: enables the **Maintenance** plugin's maintenance mode if it's installed, otherwise falls back to `/whitelist on` so no new players join mid-backup.
2. Broadcasts a "Backup in progress" message.
3. Runs `save-all` to flush world data to disk.
4. Copies (or zips, if `compress: true`) each folder listed in `include` into `destination`, asynchronously.
5. On completion, broadcasts success or failure, then **always** proceeds with the restart — a failed backup never blocks the server from restarting.
6. Prunes old backups down to `max_backups` (oldest-first by last-modified time). `max_backups: 0` keeps every backup forever.

```yaml
backup:
  enabled: false
  include: ["world", "world_nether", "world_the_end"]
  destination: "plugins/SwagRestartScheduler/backups"
  max_backups: 5
  compress: true
  maintenance_mode: true
```

- `include` — relative entries are resolved against the server's working directory; folders that don't currently exist are skipped with a console warning rather than failing the whole backup
- `destination` — same relative/absolute resolution rules
- Backup archives/folders are named with a `backup_` prefix so pruning can identify them safely

## In-game toggles

The GUI's Backup Settings page (`/srestart gui` → Backup Settings) can toggle `enabled`, `maintenance_mode`, and `compress`, and lets you set `max_backups` via a chat-input prompt — all writing directly to `config.yml` and reloading `BackupManager`. There is currently no in-game way to edit the `include` folder list or `destination` path; those still require hand-editing `config.yml`.
