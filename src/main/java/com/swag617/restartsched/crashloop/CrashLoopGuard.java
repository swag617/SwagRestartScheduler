package com.swag617.restartsched.crashloop;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.discord.DiscordNotifier;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Detects crash loops (the JVM dying without ever reaching {@code onDisable()}) and, once
 * detected, temporarily suppresses automated restart triggers so a misbehaving server isn't
 * repeatedly kicked back into whatever condition is crashing it.
 *
 * <h3>How an "unclean shutdown" is detected</h3>
 * <p>{@link #writeRunningMarker()} writes {@code shutdown.lock} to the data folder at the very
 * top of {@code onEnable()} — before any other manager initialises. {@link SwagRestartScheduler}
 * deletes that file at the very top of {@code onDisable()} via {@link #clearRunningMarker()}.
 * If the marker is already present the next time {@link #checkForUncleanShutdown()} runs, the
 * previous JVM never reached {@code onDisable()} — i.e. it crashed, was killed, or the host
 * rebooted without a graceful stop.</p>
 *
 * <h3>Rolling crash window</h3>
 * <p>Each detected unclean shutdown appends an ISO-8601 timestamp (one per line) to
 * {@code crash-log.txt} in the plugin's data folder. Only entries within
 * {@code crash-loop-safe-mode.detection-window-minutes} are kept; older lines are dropped the
 * next time the file is rewritten.</p>
 *
 * <h3>Safe mode</h3>
 * <p>If {@code crash-loop-safe-mode.unclean-shutdown-threshold} unclean shutdowns are recorded
 * within the detection window, safe mode activates for
 * {@code crash-loop-safe-mode.safe-mode-cooldown-minutes} starting from this startup. While
 * active:</p>
 * <ul>
 *   <li>{@link com.swag617.restartsched.performance.PerformanceTrigger#isEnabled()}-gated
 *       evaluation is additionally suppressed (checked alongside that existing gate).</li>
 *   <li>{@link com.swag617.restartsched.schedule.ScheduleManager#scheduleNext()} refuses to
 *       start a new <em>scheduled</em> (non-manual) restart task.</li>
 * </ul>
 * <p>Manual restarts ({@code /srestart now}, {@code /srestart in}) are never suppressed — an
 * admin who explicitly asks for a restart should always get one.</p>
 *
 * <h3>Config</h3>
 * <pre>
 * crash-loop-safe-mode:
 *   enabled: true
 *   unclean-shutdown-threshold: 2
 *   detection-window-minutes: 15
 *   safe-mode-cooldown-minutes: 30
 *   discord-message: "..."
 * </pre>
 */
public class CrashLoopGuard {

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    // ---- Config values (populated by reload()) ----
    private boolean enabled;
    private int shutdownThreshold;
    private int detectionWindowMinutes;
    private int cooldownMinutes;

    // ---- Runtime state (this JVM's lifetime) ----
    private volatile boolean safeModeActive = false;
    private volatile long safeModeExpiresAtMillis = -1L;
    private int lastDetectedCrashCount = 0;
    private boolean pendingAlert = false;

    public CrashLoopGuard(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    /** Reloads threshold/window/cooldown values from config. Safe to call on {@code /srestart reload}. */
    public void reload() {
        enabled                = plugin.getConfig().getBoolean("crash-loop-safe-mode.enabled", true);
        shutdownThreshold       = plugin.getConfig().getInt("crash-loop-safe-mode.unclean-shutdown-threshold", 2);
        detectionWindowMinutes  = plugin.getConfig().getInt("crash-loop-safe-mode.detection-window-minutes", 15);
        cooldownMinutes         = plugin.getConfig().getInt("crash-loop-safe-mode.safe-mode-cooldown-minutes", 30);
    }

    // -------------------------------------------------------------------------
    // Marker file (shutdown.lock) — "I am running" / "I shut down cleanly"
    // -------------------------------------------------------------------------

    private File shutdownMarkerFile() {
        return new File(plugin.getDataFolder(), "shutdown.lock");
    }

    private File crashLogFile() {
        return new File(plugin.getDataFolder(), "crash-log.txt");
    }

    /**
     * Must be called at the very top of {@code onEnable()}, before {@link #writeRunningMarker()}
     * and before any other manager initialises. Detects whether the previous JVM crashed (the
     * marker is still present) and, if so, records the crash and evaluates whether safe mode
     * should activate. Does not send the Discord alert directly — {@link DiscordNotifier} does
     * not exist yet this early in {@code onEnable()}; call {@link #sendPendingAlert()} later,
     * once it does.
     */
    public void checkForUncleanShutdown() {
        //noinspection ResultOfMethodCallIgnored
        plugin.getDataFolder().mkdirs();

        File marker = shutdownMarkerFile();
        if (!marker.exists()) {
            return; // previous run (if any) shut down cleanly, or this is the first run ever
        }

        logger.warning("[CrashLoopGuard] shutdown.lock was still present on startup — the previous "
                + "server instance did not shut down cleanly (crash, kill, or host reboot).");

        List<Instant> recent = recordCrashAndPrune();
        lastDetectedCrashCount = recent.size();

        if (enabled && lastDetectedCrashCount >= shutdownThreshold) {
            safeModeActive          = true;
            safeModeExpiresAtMillis = System.currentTimeMillis() + cooldownMinutes * 60_000L;
            pendingAlert            = true;
            logger.severe("[CrashLoopGuard] " + lastDetectedCrashCount + " unclean shutdown(s) within "
                    + detectionWindowMinutes + " minute(s) — entering crash-loop safe mode for "
                    + cooldownMinutes + " minute(s). Scheduled and performance-triggered restarts "
                    + "are suppressed until then.");
        }
    }

    /** Writes the "I am running" marker. Call immediately after {@link #checkForUncleanShutdown()}. */
    public void writeRunningMarker() {
        try {
            Files.writeString(shutdownMarkerFile().toPath(), Instant.now().toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("[CrashLoopGuard] Could not write shutdown.lock — crash-loop detection "
                    + "will not work correctly for this session: " + e.getMessage());
        }
    }

    /** Deletes the "running" marker. Call at the very top of {@code onDisable()}. */
    public void clearRunningMarker() {
        File marker = shutdownMarkerFile();
        if (marker.exists() && !marker.delete()) {
            logger.warning("[CrashLoopGuard] Could not delete shutdown.lock on clean shutdown — "
                    + "the next startup may incorrectly detect this as a crash.");
        }
    }

    /**
     * Sends the Discord alert for a newly detected crash loop, if one is pending from
     * {@link #checkForUncleanShutdown()}. Must be called after {@link DiscordNotifier} has been
     * constructed (later in {@code onEnable()}). Safe to call unconditionally — no-ops if
     * nothing is pending, and only ever sends once per startup.
     */
    public void sendPendingAlert() {
        if (!pendingAlert) return;
        pendingAlert = false;

        DiscordNotifier notifier = plugin.getDiscordNotifier();
        if (notifier != null) {
            notifier.sendCrashLoopAlert(lastDetectedCrashCount, detectionWindowMinutes, cooldownMinutes);
        }
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if crash-loop safe mode is currently active — i.e. scheduled and
     * performance-triggered restarts should be suppressed.
     */
    public boolean isSafeModeActive() {
        return safeModeActive && System.currentTimeMillis() < safeModeExpiresAtMillis;
    }

    // -------------------------------------------------------------------------
    // Crash log (rolling window)
    // -------------------------------------------------------------------------

    /**
     * Appends "now" to the crash log, prunes entries older than the configured detection
     * window, rewrites the file, and returns the surviving (in-window) timestamps.
     */
    private List<Instant> recordCrashAndPrune() {
        List<Instant> timestamps = readCrashLog();
        timestamps.add(Instant.now());

        Instant cutoff = Instant.now().minusSeconds(Math.max(1, detectionWindowMinutes) * 60L);
        List<Instant> inWindow = new ArrayList<>();
        for (Instant t : timestamps) {
            if (t.isAfter(cutoff)) {
                inWindow.add(t);
            }
        }

        writeCrashLog(inWindow);
        return inWindow;
    }

    private List<Instant> readCrashLog() {
        List<Instant> result = new ArrayList<>();
        File file = crashLogFile();
        if (!file.exists()) return result;

        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                try {
                    result.add(Instant.parse(trimmed));
                } catch (Exception e) {
                    logger.warning("[CrashLoopGuard] Skipping unparseable crash-log.txt line: '" + trimmed + "'");
                }
            }
        } catch (IOException e) {
            logger.warning("[CrashLoopGuard] Could not read crash-log.txt: " + e.getMessage());
        }
        return result;
    }

    private void writeCrashLog(List<Instant> timestamps) {
        StringBuilder sb = new StringBuilder();
        for (Instant t : timestamps) {
            sb.append(t.toString()).append('\n');
        }
        try {
            Files.writeString(crashLogFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("[CrashLoopGuard] Could not write crash-log.txt: " + e.getMessage());
        }
    }
}
