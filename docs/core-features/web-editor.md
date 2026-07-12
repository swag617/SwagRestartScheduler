# Web Config Editor

A single-page HTML config builder for `config.yml` and `schedules.yml`, mounted through **SwagAPI's** shared web panel at `/swagapi/swagrestartscheduler/`. Requires SwagAPI to be installed and enabled — without it, `WebEditorModule` logs a warning on startup and the editor is simply unavailable (the rest of the plugin is unaffected).

Enable/disable registration entirely with:

```yaml
web-editor:
  enabled: true
```

Get the live URL in-game with `/srestart web` (requires `swagrestart.web`) — it's posted as a clickable chat link.

## Authentication

The editor has **no login or password logic of its own**. The `/swagapi/swagrestartscheduler/` mount point is already gated by SwagAPI's own shared session-cookie system before `WebEditorHttpHandler` ever runs, so visiting the URL while not signed in redirects to SwagAPI's shared login page.

## What it actually does today

> **This is the biggest gap between "GUI editor" and reality — read this before relying on it.**
>
> The page is a client-side form. Filling it out and clicking **Save** only writes your changes to the browser's `localStorage` — there is no server-side endpoint that reads or writes the plugin's actual `config.yml` / `schedules.yml` on disk. `WebEditorHttpHandler` (the Java side) does exactly one thing: serve the static `config-editor.html` file for every request path. There is no API behind it.
>
> To actually apply changes:
>
> 1. Fill out the form (schedules, warnings, grace period, performance triggers, pre-restart commands, backup settings)
> 2. Click **Export config.yml** and/or **Export schedules.yml** — this downloads the generated YAML as files in your browser
> 3. Manually replace the corresponding files in `plugins/SwagRestartScheduler/` on the server
> 4. Run `/srestart reload` (or restart the server)
>
> The editor also has an **Import** button that reads an existing `.yml`/`.yaml` file back into the form, since the page cannot fetch the server's current config on its own — if you want to edit what's already configured, you have to upload the current file first rather than the page loading it automatically.

In short: treat it as an offline YAML-authoring tool with a nicer UI than hand-editing, not as a live remote-control panel. It's a reasonable way to avoid YAML syntax mistakes, but it does not push changes to a running server.
