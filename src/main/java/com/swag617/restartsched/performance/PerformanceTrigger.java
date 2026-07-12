package com.swag617.restartsched.performance;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.logging.RestartLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Logger;

/**
 * Evaluates server TPS against the configured threshold and triggers a
 * restart when performance has been consistently degraded.
 *
 * <h3>Configuration ({@code config.yml})</h3>
 * <pre>
 * performance_triggers:
 *   enabled: false
 *   tps_threshold: 15.0
 *   duration_minutes: 5
 *   action: "schedule"        # "schedule" | "immediate"
 *   reason: "Performance degradation detected"
 *   cooldown_minutes: 60
 * </pre>
 *
 * <h3>Actions</h3>
 * <ul>
 *   <li>{@code schedule} — schedules a restart 5 minutes out via
 *       {@link com.swag617.restartsched.schedule.ScheduleManager#startManualTask},
 *       giving warning messages time to fire.</li>
 *   <li>{@code immediate} — fires a 3-second countdown (same as {@code /restart now}).</li>
 * </ul>
 *
 * <h3>Cooldown</h3>
 * <p>After a trigger fires, the trigger will not fire again for
 * {@code cooldown_minutes}.  The cooldown is reset on plugin reload.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Call {@link #start()} once the {@link TpsMonitor} is running. Call
 * {@link #stop()} in {@code onDisable}.  Call {@link #reload()} when
 * {@code /restart reload} is executed.</p>
 */
public class PerformanceTrigger {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** How often (ticks) the evaluator checks the TpsMonitor. 20 ticks = 1 second. */
    private static final long CHECK_PERIOD_TICKS = 20L;

    /** Milliseconds in one minute — used for cooldown math. */
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final SwagRestartScheduler plugin;
    private final TpsMonitor tpsMonitor;
    private final Logger logger;

    /** Handle to the running evaluation task. */
    private volatile BukkitTask evalTask = null;

    /**
     * Epoch-millis of the last trigger fire, or {@code -1} if never fired.
     * Volatile because it can be read from the eval task (main thread) and
     * from reload (also main thread, but cleaner to be safe).
     */
    private volatile long lastTriggerMillis = -1L;

    public PerformanceTrigger(SwagRestartScheduler plugin, TpsMonitor tpsMonitor) {
        this.plugin     = plugin;
        this.tpsMonitor = tpsMonitor;
        this.logger     = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the periodic evaluation task and the TPS monitor (with the configured
     * window size derived from {@code duration_minutes}).
     */
    public void start() {
        stop();

        if (!isEnabled()) {
            logger.info("Performance triggers are disabled.");
            return;
        }

        int durationMinutes = plugin.getConfig().getInt(
                "performance_triggers.duration_minutes", 5);
        int windowSeconds = durationMinutes * 60;

        tpsMonitor.start(windowSeconds);

        evalTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::evaluate, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);

        logger.info("Performance trigger monitoring started (threshold: "
                + getTpsThreshold() + " TPS, duration: " + durationMinutes + "m).");
    }

    /**
     * Stops the evaluation task. Does NOT stop the TpsMonitor (caller should
     * call {@link TpsMonitor#stop()} separately if needed).
     */
    public void stop() {
        BukkitTask task = evalTask;
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        evalTask = null;
    }

    /**
     * Reloads configuration and restarts monitoring.
     * Resets the cooldown so that a fresh evaluation begins.
     */
    public void reload() {
        lastTriggerMillis = -1L;
        start();
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /** Returns {@code true} if performance triggers are enabled in config. */
    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("performance_triggers.enabled", false);
    }

    // -------------------------------------------------------------------------
    // Evaluation — runs on main thread
    // -------------------------------------------------------------------------

    private void evaluate() {
        if (!isEnabled()) return;

        double threshold = getTpsThreshold();
        if (!tpsMonitor.isConsistentlyBelow(threshold)) {
            return; // TPS is fine (or not enough data yet)
        }

        // Check cooldown
        int cooldownMinutes = plugin.getConfig().getInt(
                "performance_triggers.cooldown_minutes", 60);
        if (lastTriggerMillis >= 0) {
            long elapsed = System.currentTimeMillis() - lastTriggerMillis;
            if (elapsed < cooldownMinutes * MILLIS_PER_MINUTE) {
                return; // Still in cooldown window
            }
        }

        // Trigger!
        fire();
    }

    // -------------------------------------------------------------------------
    // Trigger execution — main thread
    // -------------------------------------------------------------------------

    private void fire() {
        lastTriggerMillis = System.currentTimeMillis();

        String reason = plugin.getConfig().getString(
                "performance_triggers.reason", "Performance degradation detected");
        String action = plugin.getConfig().getString(
                "performance_triggers.action", "schedule");

        double avgTps = tpsMonitor.getAverageTps();
        logger.warning("Performance trigger fired! Average TPS: "
                + String.format("%.2f", avgTps)
                + " | Action: " + action
                + " | Reason: " + reason);

        // Broadcast warning to all players
        broadcastPerformanceWarning(action);

        // Log to restart log with type "performance"
        RestartLogger restartLogger = plugin.getRestartLogger();
        if (restartLogger != null) {
            restartLogger.logPerformanceTrigger(avgTps, reason, action);
        }

        // Execute the configured action
        if ("immediate".equalsIgnoreCase(action)) {
            plugin.getScheduleManager().startManualTask(3_000L, reason, "PERFORMANCE");
        } else {
            // "schedule" — queue a restart 5 minutes out so warnings can fire
            plugin.getScheduleManager().startManualTask(5 * 60 * 1000L, reason, "PERFORMANCE");
        }

        // Stop the eval task — don't keep checking while a restart is pending
        stop();
        tpsMonitor.stop();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double getTpsThreshold() {
        return plugin.getConfig().getDouble("performance_triggers.tps_threshold", 15.0);
    }

    private void broadcastPerformanceWarning(String action) {
        String msg = action.equalsIgnoreCase("immediate")
                ? "<red><bold>Low server performance detected. Restarting immediately!"
                : "<red>Low server performance detected. Restart scheduled.";
        Component component = MM.deserialize(msg);
        for (var player : plugin.getServer().getOnlinePlayers()) {
            player.sendMessage(component);
        }
        plugin.getLogger().warning("[Broadcast] " + MM.stripTags(msg));
    }
}
