package com.swag617.restartsched.web;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * HTTP handler for the web config editor, mounted under SwagAPI's shared web server at
 * {@code /swagapi/swagrestartscheduler/} via {@link WebEditorModule}.
 *
 * <p>Authentication is handled entirely by SwagAPI's session-cookie system before this
 * handler ever runs — see {@code IWebService#registerModule}. This handler has no
 * password/login logic of its own.</p>
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@code GET /} (or empty path) — serves {@code plugins/SwagRestartScheduler/web/config-editor.html}</li>
 *   <li>{@code GET /api/config} — returns config.yml's editable sections plus schedules.yml's
 *       schedules, combined into one JSON object, so the page can load live server state
 *       instead of requiring a manual file import first</li>
 *   <li>{@code POST /api/config} — applies a JSON body (same shape as the GET response, minus
 *       {@code schedules}) to config.yml</li>
 *   <li>{@code POST /api/schedules} — applies a JSON body of the form
 *       {@code {"schedules": {"name": {...}, ...}}} to schedules.yml, wholesale-replacing the
 *       {@code schedules} section</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * <p>SwagAPI's web server dispatches every handler on a background thread pool — never the
 * main Bukkit thread (see {@code WebService#server.setExecutor(...)}). Reading/writing raw
 * JSON and parsing request bodies is safe off-thread, but touching {@code plugin.getConfig()}
 * (the same live {@link FileConfiguration} instance read every tick by
 * {@link com.swag617.restartsched.task.RestartTask} and friends) or calling any of the
 * manager {@code reload()} methods (which cancel/create {@link org.bukkit.scheduler.BukkitRunnable}s)
 * is not. All such Bukkit-API-touching work below is therefore hopped onto the main thread via
 * {@link Bukkit#getScheduler()}{@code .runTask(...)}, mirroring the {@code CompletableFuture}
 * pattern used by SwagCore's {@code DashboardApiHandler}.</p>
 */
public class WebEditorHttpHandler implements HttpHandler {

    private final SwagRestartScheduler plugin;
    private final Gson gson = new Gson();

    public WebEditorHttpHandler(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod().toUpperCase();

        try {
            if (path.equals("/") || path.isEmpty()) {
                if (!"GET".equals(method)) {
                    sendPlain(exchange, 405, "Method Not Allowed");
                    return;
                }
                serveEditorHtml(exchange);
                return;
            }

            if (path.equals("/api/config")) {
                if ("GET".equals(method)) {
                    handleGetConfig(exchange);
                } else if ("POST".equals(method)) {
                    handlePostConfig(exchange);
                } else {
                    sendPlain(exchange, 405, "Method Not Allowed");
                }
                return;
            }

            if (path.equals("/api/schedules")) {
                if ("POST".equals(method)) {
                    handlePostSchedules(exchange);
                } else {
                    sendPlain(exchange, 405, "Method Not Allowed");
                }
                return;
            }

            sendPlain(exchange, 404, "Unknown route: " + path);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Web editor request error on " + method + " " + path, e);
            try {
                sendJson(exchange, 500, "{\"error\":\"" + escapeJsonString(e.getMessage()) + "\"}");
            } catch (Exception ignored) {
                // Response likely already partially sent — nothing more we can do.
            }
        }
    }

    // -------------------------------------------------------------------------
    // GET / — static HTML
    // -------------------------------------------------------------------------

    private void serveEditorHtml(HttpExchange exchange) throws IOException {
        File htmlFile = new File(plugin.getDataFolder(), "web/config-editor.html");
        if (!htmlFile.exists()) {
            sendPlain(exchange, 404, "Editor file not found. Restart the plugin to regenerate it.");
            return;
        }

        byte[] body;
        try {
            body = Files.readAllBytes(htmlFile.toPath());
        } catch (IOException e) {
            sendPlain(exchange, 500, "Failed to read editor file: " + e.getMessage());
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/config
    // -------------------------------------------------------------------------

    private void handleGetConfig(HttpExchange exchange) throws IOException {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(buildConfigJson());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.whenComplete((data, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to build web editor config JSON", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to read current config\"}");
                } else {
                    sendJson(exchange, 200, gson.toJson(data));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web editor GET /api/config response", e);
            }
        });
    }

    /**
     * Reads {@code plugin.getConfig()} (config.yml) and {@code schedules.yml} and assembles a
     * single JSON-serializable {@link Map} matching the exact shape the config editor's
     * existing {@code importConfig()}/{@code importSchedules()} JS functions already expect
     * (they were originally written to parse an imported YAML file, but the object shape is
     * identical either way). Must be called on the main thread.
     */
    private Map<String, Object> buildConfigJson() {
        FileConfiguration cfg = plugin.getConfig();
        Map<String, Object> root = new LinkedHashMap<>();

        // ---- warnings ----
        Map<String, Object> warnings = new LinkedHashMap<>();
        warnings.put("enabled", cfg.getBoolean("warnings.enabled", true));
        warnings.put("action-bar-threshold", cfg.getInt("warnings.action-bar-threshold", 60));
        warnings.put("action-bar-format", cfg.getString("warnings.action-bar-format",
                "<red><bold>Restarting in {seconds}s"));
        List<Map<String, Object>> intervals = new ArrayList<>();
        for (Map<?, ?> raw : cfg.getMapList("warnings.intervals")) {
            Map<String, Object> iv = new LinkedHashMap<>();
            iv.put("seconds", raw.get("seconds") instanceof Number n ? n.intValue() : 0);
            iv.put("message", asString(raw.get("message"), ""));
            iv.put("title", asString(raw.get("title"), ""));
            iv.put("subtitle", asString(raw.get("subtitle"), ""));
            iv.put("sound", asString(raw.get("sound"), ""));
            intervals.add(iv);
        }
        warnings.put("intervals", intervals);
        root.put("warnings", warnings);

        // ---- grace_period ----
        Map<String, Object> grace = new LinkedHashMap<>();
        grace.put("enabled", cfg.getBoolean("grace_period.enabled", false));
        grace.put("max_delay_minutes", cfg.getInt("grace_period.max_delay_minutes", 15));
        grace.put("check_interval_seconds", cfg.getInt("grace_period.check_interval_seconds", 5));
        grace.put("message", cfg.getString("grace_period.message",
                "<yellow>Restart delayed - players in protected area"));
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("combat", cfg.getBoolean("grace_period.conditions.combat", true));
        conditions.put("worlds", cfg.getStringList("grace_period.conditions.worlds"));
        grace.put("conditions", conditions);
        root.put("grace_period", grace);

        // ---- performance_triggers ----
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("enabled", cfg.getBoolean("performance_triggers.enabled", false));
        perf.put("tps_threshold", cfg.getDouble("performance_triggers.tps_threshold", 15.0));
        perf.put("duration_minutes", cfg.getInt("performance_triggers.duration_minutes", 5));
        perf.put("action", cfg.getString("performance_triggers.action", "schedule"));
        perf.put("reason", cfg.getString("performance_triggers.reason", "Performance degradation detected"));
        perf.put("cooldown_minutes", cfg.getInt("performance_triggers.cooldown_minutes", 60));
        root.put("performance_triggers", perf);

        // ---- pre_restart ----
        Map<String, Object> pre = new LinkedHashMap<>();
        pre.put("enabled", cfg.getBoolean("pre_restart.enabled", true));
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Map<?, ?> raw : cfg.getMapList("pre_restart.commands")) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("delay", raw.get("delay") instanceof Number n ? n.intValue() : 0);
            c.put("command", asString(raw.get("command"), ""));
            c.put("executor", asString(raw.get("executor"), "console"));
            commands.add(c);
        }
        pre.put("commands", commands);
        root.put("pre_restart", pre);

        // ---- backup ----
        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("enabled", cfg.getBoolean("backup.enabled", false));
        backup.put("include", cfg.getStringList("backup.include"));
        backup.put("destination", cfg.getString("backup.destination", "plugins/SwagRestartScheduler/backups"));
        backup.put("max_backups", cfg.getInt("backup.max_backups", 5));
        backup.put("compress", cfg.getBoolean("backup.compress", true));
        backup.put("maintenance_mode", cfg.getBoolean("backup.maintenance_mode", true));
        root.put("backup", backup);

        // ---- discord ----
        Map<String, Object> discord = new LinkedHashMap<>();
        discord.put("enabled", cfg.getBoolean("discord.enabled", true));
        Map<String, Object> notifications = new LinkedHashMap<>();
        for (String type : new String[] {"scheduled_restart", "manual_restart", "server_online"}) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("enabled", cfg.getBoolean("discord.notifications." + type + ".enabled", true));
            n.put("message", cfg.getString("discord.notifications." + type + ".message", ""));
            notifications.put(type, n);
        }
        discord.put("notifications", notifications);
        root.put("discord", discord);

        // ---- schedules (schedules.yml) ----
        Map<String, Object> schedulesOut = new LinkedHashMap<>();
        FileConfiguration schedulesCfg = plugin.getConfigManager().getSchedulesConfig();
        ConfigurationSection schedulesSection = schedulesCfg != null
                ? schedulesCfg.getConfigurationSection("schedules") : null;
        if (schedulesSection != null) {
            for (String key : schedulesSection.getKeys(false)) {
                ConfigurationSection entry = schedulesSection.getConfigurationSection(key);
                if (entry == null) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("enabled", entry.getBoolean("enabled", true));
                s.put("timezone", entry.getString("timezone", "UTC"));
                s.put("days", entry.getStringList("days"));
                s.put("times", entry.getStringList("times"));
                s.put("priority", entry.getInt("priority", 1));
                schedulesOut.put(key, s);
            }
        }
        root.put("schedules", schedulesOut);

        return root;
    }

    // -------------------------------------------------------------------------
    // POST /api/config
    // -------------------------------------------------------------------------

    private void handlePostConfig(HttpExchange exchange) throws IOException {
        Map<?, ?> body;
        try {
            body = readJsonBody(exchange);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, "{\"error\":\"Malformed JSON body\"}");
            return;
        }
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                applyConfigJson(body);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.whenComplete((ignored, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to apply web editor config POST", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to save config.yml\"}");
                } else {
                    sendJson(exchange, 200, "{\"ok\":true}");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web editor POST /api/config response", e);
            }
        });
    }

    /**
     * Applies a parsed JSON body (same shape as {@link #buildConfigJson()}, minus
     * {@code schedules}) to config.yml, mirroring exactly what {@link com.swag617.restartsched.gui.BackupGUI},
     * {@link com.swag617.restartsched.gui.SettingsGUI}, and {@link com.swag617.restartsched.gui.ScheduleEditorGUI#saveToFile()}
     * already do field-by-field. Must be called on the main thread — this both mutates the
     * live {@code plugin.getConfig()} object and triggers the manager reload chain.
     */
    @SuppressWarnings("unchecked")
    private void applyConfigJson(Map<?, ?> body) {
        FileConfiguration cfg = plugin.getConfig();

        Map<String, Object> warnings = asMap(body.get("warnings"));
        if (warnings != null) {
            cfg.set("warnings.enabled", asBoolean(warnings.get("enabled"), true));
            cfg.set("warnings.action-bar-threshold", asInt(warnings.get("action-bar-threshold"), 60));
            cfg.set("warnings.action-bar-format", asString(warnings.get("action-bar-format"),
                    "<red><bold>Restarting in {seconds}s"));
            List<?> rawIntervals = warnings.get("intervals") instanceof List<?> l ? l : List.of();
            List<Map<String, Object>> intervals = new ArrayList<>();
            for (Object rawObj : rawIntervals) {
                Map<String, Object> raw = asMap(rawObj);
                if (raw == null) continue;
                Map<String, Object> iv = new LinkedHashMap<>();
                iv.put("seconds", asInt(raw.get("seconds"), 0));
                iv.put("message", asString(raw.get("message"), null));
                iv.put("title", asString(raw.get("title"), null));
                iv.put("subtitle", asString(raw.get("subtitle"), null));
                iv.put("sound", asString(raw.get("sound"), null));
                intervals.add(iv);
            }
            cfg.set("warnings.intervals", intervals);
        }

        Map<String, Object> grace = asMap(body.get("grace_period"));
        if (grace != null) {
            cfg.set("grace_period.enabled", asBoolean(grace.get("enabled"), false));
            cfg.set("grace_period.max_delay_minutes", asInt(grace.get("max_delay_minutes"), 15));
            cfg.set("grace_period.check_interval_seconds", asInt(grace.get("check_interval_seconds"), 5));
            cfg.set("grace_period.message", asString(grace.get("message"),
                    "<yellow>Restart delayed - players in protected area"));
            Map<String, Object> conditions = asMap(grace.get("conditions"));
            if (conditions != null) {
                cfg.set("grace_period.conditions.combat", asBoolean(conditions.get("combat"), true));
                cfg.set("grace_period.conditions.worlds", asStringList(conditions.get("worlds")));
            }
        }

        Map<String, Object> perf = asMap(body.get("performance_triggers"));
        if (perf != null) {
            cfg.set("performance_triggers.enabled", asBoolean(perf.get("enabled"), false));
            cfg.set("performance_triggers.tps_threshold", asDouble(perf.get("tps_threshold"), 15.0));
            cfg.set("performance_triggers.duration_minutes", asInt(perf.get("duration_minutes"), 5));
            cfg.set("performance_triggers.action", asString(perf.get("action"), "schedule"));
            cfg.set("performance_triggers.reason", asString(perf.get("reason"), "Performance degradation detected"));
            cfg.set("performance_triggers.cooldown_minutes", asInt(perf.get("cooldown_minutes"), 60));
        }

        Map<String, Object> pre = asMap(body.get("pre_restart"));
        if (pre != null) {
            cfg.set("pre_restart.enabled", asBoolean(pre.get("enabled"), true));
            List<?> rawCommands = pre.get("commands") instanceof List<?> l ? l : List.of();
            List<Map<String, Object>> commands = new ArrayList<>();
            for (Object rawObj : rawCommands) {
                Map<String, Object> raw = asMap(rawObj);
                if (raw == null) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("delay", asInt(raw.get("delay"), 0));
                c.put("command", asString(raw.get("command"), ""));
                c.put("executor", asString(raw.get("executor"), "console"));
                commands.add(c);
            }
            cfg.set("pre_restart.commands", commands);
        }

        Map<String, Object> backup = asMap(body.get("backup"));
        if (backup != null) {
            cfg.set("backup.enabled", asBoolean(backup.get("enabled"), false));
            cfg.set("backup.include", asStringList(backup.get("include")));
            cfg.set("backup.destination", asString(backup.get("destination"),
                    "plugins/SwagRestartScheduler/backups"));
            cfg.set("backup.max_backups", asInt(backup.get("max_backups"), 5));
            cfg.set("backup.compress", asBoolean(backup.get("compress"), true));
            cfg.set("backup.maintenance_mode", asBoolean(backup.get("maintenance_mode"), true));
        }

        Map<String, Object> discord = asMap(body.get("discord"));
        if (discord != null) {
            cfg.set("discord.enabled", asBoolean(discord.get("enabled"), true));
            Map<String, Object> notifications = asMap(discord.get("notifications"));
            if (notifications != null) {
                for (String type : new String[] {"scheduled_restart", "manual_restart", "server_online"}) {
                    Map<String, Object> n = asMap(notifications.get(type));
                    if (n == null) continue;
                    cfg.set("discord.notifications." + type + ".enabled", asBoolean(n.get("enabled"), true));
                    cfg.set("discord.notifications." + type + ".message", asString(n.get("message"), ""));
                }
            }
        }

        plugin.saveConfig();

        // Reload chain — mirrors RestartCommand#handleReload() exactly.
        plugin.getConfigManager().reload();
        plugin.getWarningManager().reload();
        plugin.getScheduleManager().reload();
        if (plugin.getPerformanceTrigger() != null) {
            plugin.getPerformanceTrigger().reload();
        }
        if (plugin.getBackupManager() != null) {
            plugin.getBackupManager().reload();
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/schedules
    // -------------------------------------------------------------------------

    private void handlePostSchedules(HttpExchange exchange) throws IOException {
        Map<?, ?> body;
        try {
            body = readJsonBody(exchange);
        } catch (JsonSyntaxException e) {
            sendJson(exchange, 400, "{\"error\":\"Malformed JSON body\"}");
            return;
        }
        if (body == null) {
            sendJson(exchange, 400, "{\"error\":\"Empty request body\"}");
            return;
        }

        Map<String, Object> schedules = asMap(body.get("schedules"));
        if (schedules == null) {
            sendJson(exchange, 400, "{\"error\":\"Missing 'schedules' object\"}");
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                applySchedulesJson(schedules);
                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        future.whenComplete((ignored, err) -> {
            try {
                if (err != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to apply web editor schedules POST", err);
                    sendJson(exchange, 500, "{\"error\":\"Failed to save schedules.yml\"}");
                } else {
                    sendJson(exchange, 200, "{\"ok\":true}");
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web editor POST /api/schedules response", e);
            }
        });
    }

    /**
     * Wholesale-replaces the {@code schedules} section of schedules.yml with the given map,
     * mirroring {@link com.swag617.restartsched.gui.ScheduleEditorGUI#saveToFile()}'s
     * per-field {@code .set()} pattern. Must be called on the main thread.
     */
    private void applySchedulesJson(Map<String, Object> schedules) {
        FileConfiguration schedulesCfg = plugin.getConfigManager().getSchedulesConfig();

        // Clear the existing section entirely so removed schedules actually disappear,
        // then rebuild it from the request body.
        schedulesCfg.set("schedules", null);

        for (Map.Entry<String, Object> entry : schedules.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) continue;
            Map<String, Object> s = asMap(entry.getValue());
            if (s == null) continue;

            String path = "schedules." + name;
            schedulesCfg.set(path + ".enabled", asBoolean(s.get("enabled"), true));
            schedulesCfg.set(path + ".timezone", asString(s.get("timezone"), "UTC"));
            schedulesCfg.set(path + ".days", asStringList(s.get("days")));
            schedulesCfg.set(path + ".times", asStringList(s.get("times")));
            schedulesCfg.set(path + ".priority", asInt(s.get("priority"), 1));
        }

        plugin.getConfigManager().saveConfig(schedulesCfg, plugin.getConfigManager().getSchedulesFile());
        plugin.getScheduleManager().reload();
    }

    // -------------------------------------------------------------------------
    // JSON body parsing helpers
    // -------------------------------------------------------------------------

    /** Reads and parses the request body as a JSON object. Returns {@code null} for an empty body. */
    private Map<?, ?> readJsonBody(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            Map<?, ?> parsed = gson.fromJson(reader, Map.class);
            return parsed;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }

    private boolean asBoolean(Object o, boolean def) {
        return o instanceof Boolean b ? b : def;
    }

    private int asInt(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private double asDouble(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private String asString(Object o, String def) {
        return o instanceof String s ? s : def;
    }

    private List<String> asStringList(Object o) {
        List<String> result = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) result.add(String.valueOf(item));
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // HTTP response helpers
    // -------------------------------------------------------------------------

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private void sendPlain(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private String escapeJsonString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
