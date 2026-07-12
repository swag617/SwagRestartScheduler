# Permissions

All nodes default to **op**. There is no separate "member" tier — anything below op requires manually granting these nodes through your permissions plugin.

| Permission | Default | Grants |
|---|---|---|
| `swagrestart.command` | op | Base access to `/srestart` — required for every sub-command, including `status` and `schedules` |
| `swagrestart.command.now` | op | `/srestart now` and `/srestart in` |
| `swagrestart.command.cancel` | op | `/srestart cancel` |
| `swagrestart.command.reload` | op | `/srestart reload` |
| `swagrestart.gui` | op | `/srestart gui` (opens the in-game GUI) |
| `swagrestart.bypass.grace` | op | Excludes the player from both grace-period conditions (combat and protected-world checks) |
| `swagrestart.command.logs` | op | `/srestart logs export` |
| `swagrestart.web` | op | `/srestart web` (view the web editor URL) |

> There is no dedicated permission for the in-game GUI's individual actions (editing a schedule, toggling backups, etc.) — anyone who can open the GUI via `swagrestart.gui` can use everything inside it.
