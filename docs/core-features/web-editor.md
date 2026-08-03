# Web Config Editor

A single-page HTML config builder for `config.yml` and `schedules.yml`, mounted through **SwagAPI's** shared web panel at `/swagapi/swagrestartscheduler/`. SwagAPI is a hard dependency of the whole plugin (see [Installation](../getting-started/installation.md)), so in practice its `IWebService` is always present — but `WebEditorModule` itself only looks up `IWebService` defensively at runtime and logs a warning instead of registering if that lookup ever comes back empty (e.g. SwagAPI's web server is disabled via `web-server.enabled: false`), rather than assuming it's always there.

Enable/disable registration entirely with:

```yaml
web-editor:
  enabled: true
```

Get the live URL in-game with `/srestart web` (requires `swagrestart.web`) — it's posted as a clickable chat link.

## Authentication

The editor has **no login or password logic of its own**. The `/swagapi/swagrestartscheduler/` mount point is already gated by SwagAPI's own shared session-cookie system before `WebEditorHttpHandler` ever runs, so visiting the URL while not signed in redirects to SwagAPI's shared login page.

## What it actually does today

The page loads the server's **live** `config.yml` + `schedules.yml` automatically on open (`GET /api/config`, handled by `WebEditorHttpHandler`). Editing the form and clicking **Save to Server** applies changes immediately — `POST /api/config` and `POST /api/schedules` write the files to disk and trigger the same reload chain as `/srestart reload` (config, warnings, schedules, performance triggers, backup manager). No manual file copying or plugin restart is required.

- **Load from Server** — re-fetches the live config, discarding any unsaved edits in the form
- **Save to Server** — saves and applies both files server-side
- **Export config.yml** / **Export schedules.yml** — still available for taking an offline backup or hand-editing outside the browser
- **Import** — still available for loading a `.yml`/`.yaml` file from disk into the form instead of the live server

One exception: the **Discord** section (`discord.*` in `config.yml`) has no tab in this editor and is deliberately excluded from what **Save to Server** sends — since there's no UI for it, sending a hardcoded stub would silently overwrite your real Discord settings. Edit `discord.*` directly in `config.yml` and `/srestart reload` (or use the exported YAML as a reference, where it does appear with placeholder values).
