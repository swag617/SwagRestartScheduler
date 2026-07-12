package com.swag617.restartsched.automation;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Schedules console commands to run at configured intervals before a server restart.
 *
 * <h3>Configuration shape ({@code config.yml})</h3>
 * <pre>
 * pre_restart:
 *   enabled: true
 *   commands:
 *     - delay: 300           # seconds before restart
 *       command: "broadcast ..."
 *       executor: "console"  # only "console" is supported
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Call {@link #schedule(long)} with the epoch-millis of the upcoming restart.
 *       This method calculates how many ticks from now each command should fire and
 *       enqueues them via {@link Bukkit#getScheduler()}.</li>
 *   <li>Call {@link #cancelAll()} if the restart is cancelled.  All pending tasks
 *       are cancelled and the list is cleared.</li>
 * </ol>
 *
 * <p>PlaceholderAPI substitution is applied if PAPI is loaded, using a
 * {@code null} player context (console/offline).</p>
 */
public class PreRestartCommandExecutor {

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    /** Handles to all pending pre-restart command tasks. */
    private final List<BukkitTask> pending = new ArrayList<>();

    public PreRestartCommandExecutor(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reads the {@code pre_restart.commands} list from config and schedules each
     * entry to fire at the appropriate time before {@code restartAtMillis}.
     *
     * <p>Entries whose fire time is already in the past are skipped.</p>
     *
     * @param restartAtMillis epoch-millis of the scheduled restart
     */
    public void schedule(long restartAtMillis) {
        cancelAll(); // clear any previous batch

        if (!plugin.getConfig().getBoolean("pre_restart.enabled", true)) return;

        var commandList = plugin.getConfig().getMapList("pre_restart.commands");
        if (commandList == null || commandList.isEmpty()) return;

        long nowMillis = System.currentTimeMillis();

        for (var rawEntry : commandList) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) rawEntry;

            Object delayObj = entry.get("delay");
            if (!(delayObj instanceof Number)) {
                logger.warning("pre_restart command entry missing 'delay' (number) field — skipped.");
                continue;
            }
            long delaySecs = ((Number) delayObj).longValue();
            if (delaySecs < 0) {
                logger.warning("pre_restart command entry has negative delay — skipped.");
                continue;
            }

            Object cmdObj = entry.get("command");
            if (!(cmdObj instanceof String command) || command.isBlank()) {
                logger.warning("pre_restart command entry missing 'command' string — skipped.");
                continue;
            }

            // Compute when this command should fire
            long fireAtMillis = restartAtMillis - (delaySecs * 1000L);
            long delayMillis  = fireAtMillis - nowMillis;

            if (delayMillis <= 0) {
                // Already past — fire immediately
                logger.fine("Pre-restart command is past its fire time; executing now: " + command);
                executeCommand(command);
                continue;
            }

            // Convert milliseconds to ticks (20 ticks/sec), rounding to nearest tick
            long delayTicks = Math.max(1L, delayMillis / 50L);
            final String finalCommand = command;

            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                executeCommand(finalCommand);
            }, delayTicks);

            pending.add(task);
            logger.fine("Scheduled pre-restart command in " + (delayMillis / 1000L) + "s: " + command);
        }

        if (!pending.isEmpty()) {
            logger.info("Scheduled " + pending.size() + " pre-restart command(s).");
        }
    }

    /**
     * Cancels all pending pre-restart command tasks.
     * Safe to call even if no tasks are pending.
     */
    public void cancelAll() {
        if (pending.isEmpty()) return;
        int count = 0;
        for (BukkitTask task : pending) {
            if (!task.isCancelled()) {
                task.cancel();
                count++;
            }
        }
        pending.clear();
        if (count > 0) {
            logger.info("Cancelled " + count + " pre-restart command task(s).");
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    /**
     * Executes a console command after optionally applying PlaceholderAPI
     * substitution (using {@code null} player for console/offline context).
     */
    private void executeCommand(String command) {
        String processed = applyPlaceholders(command);
        logger.info("[PreRestart] Executing: " + processed);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processed);
    }

    /**
     * Applies PlaceholderAPI substitution if PAPI is loaded.
     * Uses a {@code null} player (offline/console context).
     */
    private String applyPlaceholders(String input) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return input;
        }
        try {
            Class<?> papiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            java.lang.reflect.Method method = papiClass.getMethod(
                    "setPlaceholders", org.bukkit.OfflinePlayer.class, String.class);
            Object result = method.invoke(null, null, input);
            return result instanceof String s ? s : input;
        } catch (Exception e) {
            // PAPI present but reflection failed — return input unchanged
            logger.fine("PlaceholderAPI substitution failed for pre-restart command: " + e.getMessage());
            return input;
        }
    }
}
