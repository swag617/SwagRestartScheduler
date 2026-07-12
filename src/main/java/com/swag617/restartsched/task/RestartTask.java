package com.swag617.restartsched.task;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.backup.BackupManager;
import com.swag617.restartsched.discord.DiscordNotifier;
import com.swag617.restartsched.grace.GracePeriodHandler;
import com.swag617.restartsched.logging.RestartLogger;
import com.swag617.restartsched.schedule.ScheduleManager;
import com.swag617.restartsched.warning.WarningManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Logger;

/**
 * The core countdown runnable.
 *
 * <p>Runs every tick (every 1 game tick = 50 ms) on the main server thread.
 * Uses {@link System#currentTimeMillis()} as the time source for warning
 * precision rather than tick counts, which can drift under server load.</p>
 *
 * <p>When the countdown expires the task first checks the optional
 * {@link GracePeriodHandler}; if conditions require a delay it re-schedules
 * itself for {@code check_interval_seconds} later, up to {@code max_delay_minutes}.
 * Once conditions clear (or the max delay elapses) the actual restart executes.</p>
 *
 * <p>A task is either <em>manual</em> (triggered by a command) or
 * <em>scheduled</em> (triggered by {@link ScheduleManager}).
 * Only manual tasks can be cancelled via {@code /restart cancel}.</p>
 */
public class RestartTask extends BukkitRunnable {

    /** How often the runnable fires, in ticks. */
    private static final long PERIOD_TICKS = 1L;

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    /** Wall-clock time (epoch millis) at which the restart should occur. */
    private final long restartAtMillis;

    private final String reason;
    private final String initiator;
    private final String sourceName;
    private final boolean manual;

    // ---- Optional integrations (may be null) --------------------------------
    private final GracePeriodHandler gracePeriodHandler;
    private final DiscordNotifier    discordNotifier;
    // -------------------------------------------------------------------------

    /**
     * Epoch-millis at which the countdown first hit zero.
     * Used to enforce {@code max_delay_minutes} in the grace period.
     * -1 means the countdown has not yet hit zero.
     */
    private long gracePeriodStartMillis = -1L;

    /**
     * Wall-clock time of the last second boundary we processed a tick for.
     * Used to ensure we fire warning/action-bar logic at most once per second.
     */
    private long lastTickedSecond = -1L;

    /** Whether this task has already triggered the restart. */
    private volatile boolean executed = false;

    /**
     * @param plugin                plugin instance
     * @param millisUntil           milliseconds from now until restart
     * @param reason                human-readable reason string
     * @param initiator             display name of the initiating entity
     *                              (player name, "SCHEDULE", "CONSOLE")
     * @param sourceName            schedule name or "manual"
     * @param gracePeriodHandler    optional grace-period checker; may be {@code null}
     * @param discordNotifier       optional Discord notifier; may be {@code null}
     */
    public RestartTask(SwagRestartScheduler plugin, long millisUntil,
                       String reason, String initiator, String sourceName,
                       GracePeriodHandler gracePeriodHandler,
                       DiscordNotifier discordNotifier) {
        this.plugin               = plugin;
        this.logger               = plugin.getLogger();
        this.restartAtMillis      = System.currentTimeMillis() + millisUntil;
        this.reason               = reason    != null ? reason    : "Scheduled restart";
        this.initiator            = initiator != null ? initiator : "UNKNOWN";
        this.sourceName           = sourceName != null ? sourceName : "unknown";
        this.manual               = !"SCHEDULE".equals(initiator);
        this.gracePeriodHandler   = gracePeriodHandler;
        this.discordNotifier      = discordNotifier;
    }

    // -------------------------------------------------------------------------
    // BukkitRunnable
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        if (executed) return;

        long now             = System.currentTimeMillis();
        long millisRemaining = restartAtMillis - now;

        if (millisRemaining <= 0) {
            checkGraceOrRestart();
            return;
        }

        // Only fire warning/action-bar logic once per second boundary
        long currentSecond = millisRemaining / 1000L;
        if (currentSecond != lastTickedSecond) {
            lastTickedSecond = currentSecond;
            plugin.getWarningManager().tick(millisRemaining);
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers and starts this task on the main-thread scheduler.
     * Also notifies {@link com.swag617.restartsched.automation.PreRestartCommandExecutor}
     * so it can queue pre-restart commands.
     */
    public void start() {
        long totalMillis = restartAtMillis - System.currentTimeMillis();
        plugin.getWarningManager().reset(totalMillis);

        // Schedule pre-restart commands
        if (plugin.getPreRestartCommandExecutor() != null) {
            plugin.getPreRestartCommandExecutor().schedule(restartAtMillis);
        }

        runTaskTimer(plugin, 0L, PERIOD_TICKS);
    }

    /**
     * Cancels this task without executing the restart.
     * Also cancels any queued pre-restart commands.
     * Safe to call multiple times.
     */
    public void cancelTask() {
        // Cancel pre-restart commands
        if (plugin.getPreRestartCommandExecutor() != null) {
            plugin.getPreRestartCommandExecutor().cancelAll();
        }

        if (!isCancelled()) {
            try {
                cancel();
            } catch (IllegalStateException ignored) {
                // Already cancelled — safe to ignore
            }
        }
        plugin.getScheduleManager().onTaskComplete(this);
    }

    /** Returns {@code true} if this task was initiated manually (via a command). */
    public boolean isManual() {
        return manual;
    }

    /** Milliseconds remaining until restart, or 0 if already past. */
    public long getMillisRemaining() {
        return Math.max(0L, restartAtMillis - System.currentTimeMillis());
    }

    public String getReason() {
        return reason;
    }

    public String getInitiator() {
        return initiator;
    }

    public String getSourceName() {
        return sourceName;
    }

    // -------------------------------------------------------------------------
    // Grace period
    // -------------------------------------------------------------------------

    /**
     * Called when the countdown reaches zero.  Checks whether the grace period
     * handler wants to delay the restart; if so, defers by one check interval.
     * If no delay is needed (or grace period is disabled), executes the restart.
     */
    private void checkGraceOrRestart() {
        // Record when we first hit zero so max_delay_minutes can be enforced
        if (gracePeriodStartMillis < 0) {
            gracePeriodStartMillis = System.currentTimeMillis();
        }

        if (gracePeriodHandler != null && gracePeriodHandler.shouldDelay(gracePeriodStartMillis)) {
            // Conditions require a delay — cancel the per-tick timer and schedule a
            // one-shot re-check after the configured check interval.
            long delayTicks = gracePeriodHandler.getCheckIntervalTicks();
            if (!isCancelled()) {
                try { cancel(); } catch (IllegalStateException ignored) { }
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!executed) {
                    checkGraceOrRestart();
                }
            }, delayTicks);
            return;
        }

        executeRestart();
    }

    // -------------------------------------------------------------------------
    // Restart execution
    // -------------------------------------------------------------------------

    private void executeRestart() {
        if (executed) return;
        executed = true;

        if (!isCancelled()) {
            try {
                cancel();
            } catch (IllegalStateException ignored) { }
        }

        // Log before the server goes down
        RestartLogger restartLogger = plugin.getRestartLogger();
        restartLogger.logRestart(initiator, reason, sourceName, manual ? "MANUAL" : "SCHEDULED");

        logger.info("Executing restart. Initiator: " + initiator
                + " | Reason: " + reason + " | Source: " + sourceName);

        // Discord notification (async — fire and hope; server is about to shut down)
        if (discordNotifier != null) {
            if (manual) {
                discordNotifier.sendManualRestartNotification(reason, initiator);
            }
            // For scheduled restarts the "scheduled_restart" notification was already
            // sent by WarningManager's first warning broadcast; a final "restarting now"
            // ping here is intentionally omitted to avoid duplicate messages.
        }

        // If backup is enabled, run it first — doRestart() is the callback
        BackupManager backupManager = plugin.getBackupManager();
        if (backupManager != null && backupManager.isEnabled()) {
            backupManager.runBackup(this::doRestart);
        } else {
            doRestart();
        }
    }

    /**
     * Performs the actual server stop.  Separated from {@link #executeRestart()}
     * so that {@link BackupManager} can invoke it as a post-backup callback on
     * the main thread.
     */
    private void doRestart() {
        boolean useSpigotRestart = plugin.getConfig().getBoolean("general.use-spigot-restart", true);
        if (useSpigotRestart) {
            try {
                plugin.getServer().spigot().restart();
                return;
            } catch (Exception e) {
                logger.warning("spigot().restart() failed (" + e.getMessage() + ") — falling back to shutdown().");
            }
        }
        plugin.getServer().shutdown();
    }
}
