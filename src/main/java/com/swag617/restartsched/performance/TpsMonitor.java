package com.swag617.restartsched.performance;

import com.swag617.restartsched.SwagRestartScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Logger;

/**
 * Tracks server TPS by polling {@code Bukkit.getServer().getTPS()} every 20 ticks
 * and maintaining a rolling window of recent readings.
 *
 * <p>Paper exposes TPS as a {@code double[]} where index 0 is the 1-minute average,
 * index 1 is the 5-minute average, and index 2 is the 15-minute average.
 * This monitor samples the 1-minute average once per second (every 20 ticks) and
 * stores the last {@code windowSize} readings for evaluation by
 * {@link PerformanceTrigger}.</p>
 *
 * <h3>Thread safety</h3>
 * <p>The window array is accessed under a lock; {@link #getAverageTps()} and
 * {@link #isConsistentlyBelow(double)} are safe to call from any thread.</p>
 */
public class TpsMonitor {

    /** Ticks between samples (20 ticks = 1 second). */
    private static final long SAMPLE_PERIOD_TICKS = 20L;

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    /** Circular buffer of TPS readings. */
    private double[] window;
    /** Next write index in the circular buffer. */
    private int writeIndex = 0;
    /** How many samples have been recorded (up to window.length). */
    private int sampleCount = 0;

    /** Lock object for the window fields above. */
    private final Object windowLock = new Object();

    /** Handle to the running poll task; null when stopped. */
    private volatile BukkitTask pollTask = null;

    public TpsMonitor(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        // Default window size; will be resized on start() with the configured value
        this.window = new double[300]; // 5 minutes at 1 sample/sec
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the TPS sampling task with the given window size.
     * Safe to call on reload — stops any existing task first.
     *
     * @param windowSeconds number of seconds of history to retain
     */
    public void start(int windowSeconds) {
        stop();

        int size = Math.max(1, windowSeconds);
        synchronized (windowLock) {
            window = new double[size];
            writeIndex  = 0;
            sampleCount = 0;
        }

        pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sample,
                SAMPLE_PERIOD_TICKS, SAMPLE_PERIOD_TICKS);
        logger.info("TPS monitor started (window: " + size + "s).");
    }

    /**
     * Stops the sampling task. Safe to call even if already stopped.
     */
    public void stop() {
        BukkitTask task = pollTask;
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        pollTask = null;
    }

    // -------------------------------------------------------------------------
    // Queries (thread-safe)
    // -------------------------------------------------------------------------

    /**
     * Returns the average TPS across all recorded samples in the current window.
     * Returns 20.0 if no samples have been recorded yet.
     */
    public double getAverageTps() {
        synchronized (windowLock) {
            if (sampleCount == 0) return 20.0;
            double sum = 0.0;
            int limit = Math.min(sampleCount, window.length);
            for (int i = 0; i < limit; i++) {
                sum += window[i];
            }
            return sum / limit;
        }
    }

    /**
     * Returns {@code true} if <em>all</em> samples in the current window are
     * below {@code threshold}, AND the window is full (enough data has been
     * collected to make a reliable determination).
     *
     * @param threshold TPS value below which performance is considered degraded
     */
    public boolean isConsistentlyBelow(double threshold) {
        synchronized (windowLock) {
            if (sampleCount < window.length) {
                // Not enough samples yet
                return false;
            }
            for (double tps : window) {
                if (tps >= threshold) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Returns the number of samples currently recorded in the window.
     */
    public int getSampleCount() {
        synchronized (windowLock) {
            return sampleCount;
        }
    }

    /**
     * Returns the configured window size (total capacity of the rolling buffer).
     */
    public int getWindowSize() {
        synchronized (windowLock) {
            return window.length;
        }
    }

    // -------------------------------------------------------------------------
    // Internal — runs on main thread (BukkitTask)
    // -------------------------------------------------------------------------

    private void sample() {
        double[] serverTps = plugin.getServer().getTPS();
        // Index 0 = 1-minute average; clamp to [0, 20] for sanity
        double tps = (serverTps != null && serverTps.length > 0)
                ? Math.min(20.0, Math.max(0.0, serverTps[0]))
                : 20.0;

        synchronized (windowLock) {
            window[writeIndex] = tps;
            writeIndex = (writeIndex + 1) % window.length;
            if (sampleCount < window.length) {
                sampleCount++;
            }
        }
    }
}
