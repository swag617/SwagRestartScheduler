package com.swag617.restartsched.command;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.schedule.ScheduleManager;
import com.swag617.restartsched.schedule.RestartSchedule;
import com.swag617.restartsched.task.RestartTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles all {@code /restart} sub-commands and tab-completion.
 *
 * <h3>Sub-commands</h3>
 * <ul>
 *   <li>{@code /restart now [reason]}              — immediate restart</li>
 *   <li>{@code /restart in <time> [reason]}        — timed restart</li>
 *   <li>{@code /restart cancel}                    — cancel pending manual restart</li>
 *   <li>{@code /restart status}                    — show next restart info</li>
 *   <li>{@code /restart schedules}                 — list all named schedules</li>
 *   <li>{@code /restart reload}                    — reload all configs</li>
 *   <li>{@code /restart logs export}               — show path to CSV log file</li>
 * </ul>
 */
public class RestartCommand implements CommandExecutor, TabCompleter {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z");

    private static final List<String> SUB_COMMANDS = List.of(
            "now", "in", "cancel", "status", "schedules", "reload", "gui", "logs", "web"
    );

    private static final List<String> LOGS_SUB_COMMANDS = List.of("export");

    private final SwagRestartScheduler plugin;

    public RestartCommand(SwagRestartScheduler plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // CommandExecutor
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        if (!sender.hasPermission("swagrestart.command")) {
            send(sender, getMessage("no-permission"));
            return true;
        }

        if (args.length == 0) {
            send(sender, getMessage("unknown-subcommand"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "now"       -> handleNow(sender, args);
            case "in"        -> handleIn(sender, args);
            case "cancel"    -> handleCancel(sender);
            case "status"    -> handleStatus(sender);
            case "schedules" -> handleSchedules(sender);
            case "reload"    -> handleReload(sender);
            case "gui"       -> handleGui(sender);
            case "logs"      -> handleLogs(sender, args);
            case "web"       -> handleWeb(sender);
            default          -> send(sender, getMessage("unknown-subcommand"));
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Sub-command handlers
    // -------------------------------------------------------------------------

    private void handleNow(CommandSender sender, String[] args) {
        if (!sender.hasPermission("swagrestart.command.now")) {
            send(sender, getMessage("no-permission"));
            return;
        }

        String reason = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "Manual restart";

        String initiator = sender.getName();

        // Cancel existing manual task if present (mirrors handleIn) — otherwise this
        // command would silently no-op while a previous manual countdown kept running,
        // since ScheduleManager#startManualTask() refuses to replace a running manual task.
        ScheduleManager sm = plugin.getScheduleManager();
        RestartTask existing = sm.getActiveTask();
        if (existing != null && existing.isManual()) {
            existing.cancelTask();
        }

        // Build broadcast to all players
        String broadcastRaw = getMessage("restart-initiated").replace("{reason}", reason);
        broadcastAll(broadcastRaw);

        // Log-level message to console / sender
        String consoleMsg = getMessageRaw("restart-initiated-console")
                .replace("{initiator}", initiator)
                .replace("{reason}", reason);
        plugin.getLogger().info(MM.stripTags(consoleMsg));

        // Kick off with a tiny delay so broadcast is received before the server stops
        sm.startManualTask(3_000L, reason, initiator);
    }

    private void handleIn(CommandSender sender, String[] args) {
        if (!sender.hasPermission("swagrestart.command.now")) {
            send(sender, getMessage("no-permission"));
            return;
        }

        if (args.length < 2) {
            send(sender, getMessage("usage-restart-in"));
            return;
        }

        long seconds = parseTime(args[1]);
        if (seconds < 0) {
            send(sender, getMessage("restart-in-bad-format"));
            return;
        }
        if (seconds < 10) {
            send(sender, getMessage("restart-in-too-short"));
            return;
        }

        String reason = args.length > 2
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "Scheduled manual restart";

        String humanTime = ScheduleManager.formatDuration(seconds * 1000L);
        String initiator = sender.getName();

        // Cancel existing manual task if present
        ScheduleManager sm = plugin.getScheduleManager();
        RestartTask existing = sm.getActiveTask();
        if (existing != null && existing.isManual()) {
            existing.cancelTask();
        }

        sm.startManualTask(seconds * 1000L, reason, initiator);

        // Notify sender
        String senderMsg = getMessage("restart-in-initiated").replace("{time}", humanTime);
        send(sender, senderMsg);

        // Broadcast to all
        String broadcastRaw = getMessageRaw("broadcast-restart-in").replace("{time}", humanTime);
        broadcastAll(broadcastRaw);
    }

    private void handleCancel(CommandSender sender) {
        if (!sender.hasPermission("swagrestart.command.cancel")) {
            send(sender, getMessage("no-permission"));
            return;
        }

        boolean cancelled = plugin.getScheduleManager().cancelManualTask();
        if (cancelled) {
            send(sender, getMessage("cancel-success"));
            broadcastAll(getMessageRaw("broadcast-cancelled"));
        } else {
            send(sender, getMessage("cancel-no-pending"));
        }
    }

    private void handleStatus(CommandSender sender) {
        RestartTask active = plugin.getScheduleManager().getActiveTask();

        if (active == null) {
            // Fall back to checking the next scheduled time
            Optional<ScheduleManager.RestartWithSchedule> next =
                    plugin.getScheduleManager().getNextRestartWithSchedule();
            if (next.isEmpty()) {
                send(sender, getMessage("status-no-pending"));
            } else {
                ZonedDateTime time     = next.get().restartTime();
                String        timeStr  = time.format(DISPLAY_FMT);
                long          millis   = time.toInstant().toEpochMilli() - System.currentTimeMillis();
                String        duration = ScheduleManager.formatDuration(millis);
                String        source   = next.get().schedule().getName();
                String msg = getMessageRaw("status-pending")
                        .replace("{time}", timeStr + " (in " + duration + ")")
                        .replace("{source}", source);
                send(sender, msg, true);
            }
            return;
        }

        long   millis   = active.getMillisRemaining();
        String duration = ScheduleManager.formatDuration(millis);
        String msg = getMessageRaw("status-pending")
                .replace("{time}", "in " + duration)
                .replace("{source}", active.getSourceName()
                        + (active.isManual() ? " [manual]" : " [scheduled]"));
        send(sender, msg, true);
    }

    private void handleSchedules(CommandSender sender) {
        List<RestartSchedule> schedules = plugin.getScheduleManager().getSchedules();

        send(sender, getMessage("schedules-header"), false);

        if (schedules.isEmpty()) {
            send(sender, getMessage("schedules-none"), false);
            return;
        }

        for (RestartSchedule sched : schedules) {
            String days  = sched.getDays().stream()
                    .map(d -> d.name().substring(0, 3))
                    .collect(Collectors.joining(", "));
            String times = sched.getTimes().stream()
                    .map(t -> t.toString())
                    .collect(Collectors.joining(", "));

            String next = sched.getNextRestart()
                    .map(zdt -> zdt.format(DISPLAY_FMT))
                    .orElse("none");

            String line = getMessageRaw("schedules-entry")
                    .replace("{name}",     sched.getName())
                    .replace("{enabled}",  String.valueOf(sched.isEnabled()))
                    .replace("{timezone}", sched.getTimezone().getId())
                    .replace("{days}",     days)
                    .replace("{times}",    times)
                    .replace("{next}",     next);
            send(sender, line, false);
        }
    }

    private void handleGui(CommandSender sender) {
        if (!sender.hasPermission("swagrestart.gui")) {
            send(sender, getMessage("no-permission"));
            return;
        }
        if (!(sender instanceof Player player)) {
            send(sender, getMessage("must-be-player"));
            return;
        }
        plugin.getGUIManager().openMainMenu(player);
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("swagrestart.command.reload")) {
            send(sender, getMessage("no-permission"));
            return;
        }

        try {
            plugin.getConfigManager().reload();
            plugin.getWarningManager().reload();
            plugin.getScheduleManager().reload();

            // Reload performance trigger if it exists
            if (plugin.getPerformanceTrigger() != null) {
                plugin.getPerformanceTrigger().reload();
            }

            // Reload backup manager if it exists
            if (plugin.getBackupManager() != null) {
                plugin.getBackupManager().reload();
            }

            // Reload crash-loop safe mode thresholds if it exists
            if (plugin.getCrashLoopGuard() != null) {
                plugin.getCrashLoopGuard().reload();
            }

            send(sender, getMessage("reload-success"));
        } catch (Exception e) {
            String errMsg = getMessage("reload-failed").replace("{error}", e.getMessage());
            send(sender, errMsg);
            plugin.getLogger().severe("Reload failed: " + e.getMessage());
        }
    }

    /**
     * Handles {@code /restart logs <subcommand>}.
     *
     * <p>Currently only {@code export} is supported, which tells the sender
     * where the CSV log file is located on disk.</p>
     */
    private void handleLogs(CommandSender sender, String[] args) {
        if (!sender.hasPermission("swagrestart.command.logs")) {
            send(sender, getMessage("no-permission"));
            return;
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("export")) {
            send(sender, "<red>Usage: <white>/restart logs export", false);
            return;
        }

        // "export" — report the CSV file path
        java.io.File csvFile = plugin.getRestartLogger().getCsvFile();
        String path = csvFile.getAbsolutePath();

        send(sender, "<green>Logs exported to: <white>" + path, false);
        plugin.getLogger().info(sender.getName() + " requested log export path: " + path);
    }

    /**
     * Handles {@code /srestart web}.
     *
     * <p>Sends a clickable Adventure component pointing to the embedded web
     * config editor.  The URL is opened in the player's default browser via
     * {@link ClickEvent#openUrl(String)}.</p>
     */
    private void handleWeb(CommandSender sender) {
        if (!sender.hasPermission("swagrestart.web")) {
            send(sender, getMessage("no-permission"));
            return;
        }

        var webEditor = plugin.getWebEditorModule();
        String url = webEditor != null ? webEditor.getUrl() : null;
        if (url == null) {
            send(sender, "<red>Web editor is not available — is SwagAPI installed and enabled?", false);
            return;
        }

        Component urlComponent = Component.text(url)
                .color(TextColor.fromHexString("#00d4ff"))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Click to open in browser")));

        sender.sendMessage(MM.deserialize("<gold>Web Config Editor ").append(urlComponent));
    }

    // -------------------------------------------------------------------------
    // TabCompleter
    // -------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (!sender.hasPermission("swagrestart.command")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filterByPrefix(SUB_COMMANDS, args[0]);
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "in"   -> { return List.of("30s", "1m", "5m", "10m", "30m", "1h", "2h"); }
                case "logs" -> { return filterByPrefix(LOGS_SUB_COMMANDS, args[1]); }
            }
        }

        return Collections.emptyList();
    }

    // -------------------------------------------------------------------------
    // Time parsing
    // -------------------------------------------------------------------------

    /**
     * Parses strings like "30m", "1h", "1h30m", "45s", "2h15m30s".
     *
     * @return total seconds, or -1 if the string is invalid
     */
    public static long parseTime(String input) {
        if (input == null || input.isBlank()) return -1;

        long total = 0;
        StringBuilder num = new StringBuilder();

        for (char c : input.toLowerCase().toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (c == 'h' || c == 'm' || c == 's') {
                if (num.isEmpty()) return -1;
                long value = Long.parseLong(num.toString());
                num.setLength(0);
                total += switch (c) {
                    case 'h' -> value * 3600;
                    case 'm' -> value * 60;
                    case 's' -> value;
                    default  -> 0;
                };
            } else {
                return -1; // unexpected character
            }
        }

        // Trailing digits with no unit — treat as seconds
        if (!num.isEmpty()) {
            total += Long.parseLong(num.toString());
        }

        return total > 0 ? total : -1;
    }

    // -------------------------------------------------------------------------
    // Messaging helpers
    // -------------------------------------------------------------------------

    private void send(CommandSender sender, String mmString) {
        send(sender, mmString, true);
    }

    private void send(CommandSender sender, String mmString, boolean withPrefix) {
        if (mmString == null || mmString.isBlank()) return;
        String finalMsg = withPrefix
                ? plugin.getConfigManager().getMessage("prefix", false) + mmString
                : mmString;
        sender.sendMessage(MM.deserialize(finalMsg));
    }

    private void broadcastAll(String mmString) {
        if (mmString == null || mmString.isBlank()) return;
        Component component = MM.deserialize(mmString);
        for (var player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(component);
        }
        // Also log the stripped text to console
        plugin.getLogger().info("[Broadcast] " + MM.stripTags(mmString));
    }

    /**
     * Returns a message with the configured prefix prepended.
     */
    private String getMessage(String key) {
        return plugin.getConfigManager().getMessage(key, false);
    }

    /**
     * Returns a raw MiniMessage string without prefix, for use in
     * further string replacement before sending.
     */
    private String getMessageRaw(String key) {
        return plugin.getConfigManager().getMessage(key, false);
    }

    private List<String> filterByPrefix(List<String> options, String prefix) {
        if (prefix == null || prefix.isBlank()) return new ArrayList<>(options);
        return options.stream()
                .filter(o -> o.toLowerCase().startsWith(prefix.toLowerCase()))
                .collect(Collectors.toList());
    }
}
