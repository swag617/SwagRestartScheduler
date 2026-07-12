package com.swag617.restartsched.schedule;

import com.swag617.restartsched.SwagRestartScheduler;
import com.swag617.restartsched.discord.DiscordNotifier;
import com.swag617.restartsched.grace.GracePeriodHandler;
import com.swag617.restartsched.task.RestartTask;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

/**
 * Thread-safe manager for all {@link RestartSchedule} instances.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Loading and validating schedules from {@code schedules.yml}</li>
 *   <li>Finding the next restart across all enabled schedules with proper priority handling</li>
 *   <li>Starting and stopping the {@link RestartTask} Bukkit runnable</li>
 * </ul>
 *
 * <h3>Priority semantics (Phase 3)</h3>
 * <p>The earliest restart time always wins.  When two schedules have a next
 * restart within 30 seconds of each other they are considered a tie, and the
 * schedule with the <em>lowest</em> priority number is preferred (1 = highest
 * priority).</p>
 *
 * <p>The schedule list is protected by a {@link ReentrantReadWriteLock}, and the
 * active {@link RestartTask} is stored in an {@link AtomicReference} to allow
 * safe reads from any thread without holding the write lock.</p>
 */
public class ScheduleManager {

    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),  // "3:30 PM", "12:00 AM"
            DateTimeFormatter.ofPattern("h a",    Locale.ENGLISH),  // "3 AM", "12 PM"
            DateTimeFormatter.ofPattern("HH:mm",  Locale.ENGLISH),  // "15:00", "03:00"
            DateTimeFormatter.ofPattern("H:mm",   Locale.ENGLISH)   // "3:00"
    );

    /**
     * Two schedules whose next restart times are within this many milliseconds
     * of each other are treated as a tie, resolved by lowest priority number.
     */
    private static final long TIEBREAK_WINDOW_MILLIS = 30_000L;

    private final SwagRestartScheduler plugin;
    private final Logger logger;

    /** Guards {@link #schedules}. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Loaded schedule definitions. Replaced wholesale on reload. */
    private List<RestartSchedule> schedules = new ArrayList<>();

    /**
     * The currently active {@link RestartTask} (scheduled or manual).
     * {@code null} when no restart is pending.
     */
    private final AtomicReference<RestartTask> activeTask = new AtomicReference<>(null);

    public ScheduleManager(SwagRestartScheduler plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    // -------------------------------------------------------------------------
    // Convenience accessors for integrations
    // -------------------------------------------------------------------------

    private GracePeriodHandler getGracePeriodHandler() {
        return plugin.getGracePeriodHandler();
    }

    private DiscordNotifier getDiscordNotifier() {
        return plugin.getDiscordNotifier();
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Loads schedules from {@code schedules.yml} and starts the scheduling task.
     * Safe to call on reload — cancels any existing scheduled (non-manual) task first.
     */
    public void reload() {
        lock.writeLock().lock();
        try {
            schedules = loadSchedules();
        } finally {
            lock.writeLock().unlock();
        }

        // Cancel the existing scheduled task only if it was not manually triggered
        RestartTask existing = activeTask.get();
        if (existing != null && !existing.isManual()) {
            existing.cancelTask();
            activeTask.set(null);
        }

        scheduleNext();
    }

    /**
     * Cancels all running tasks. Called from {@code onDisable}.
     */
    public void shutdown() {
        RestartTask task = activeTask.getAndSet(null);
        if (task != null) {
            task.cancelTask();
        }
    }

    // -------------------------------------------------------------------------
    // Task management
    // -------------------------------------------------------------------------

    /**
     * Finds the next restart time across all enabled schedules and starts a
     * {@link RestartTask} to count down to it.  Does nothing if a task is
     * already running (manual or scheduled).
     */
    public void scheduleNext() {
        // Never override a running task
        if (activeTask.get() != null) {
            return;
        }

        Optional<RestartWithSchedule> next = getNextRestartWithSchedule();
        if (next.isEmpty()) {
            logger.fine("No upcoming scheduled restarts found.");
            return;
        }

        ZonedDateTime   restartTime    = next.get().restartTime();
        RestartSchedule sourceSchedule = next.get().schedule();
        long millisUntil = restartTime.toInstant().toEpochMilli() - System.currentTimeMillis();

        if (millisUntil <= 0) {
            logger.warning("Next restart time is in the past — skipping.");
            return;
        }

        String sourceName = sourceSchedule.getName();
        RestartTask task = new RestartTask(plugin, millisUntil, "Scheduled restart", "SCHEDULE",
                sourceName, getGracePeriodHandler(), getDiscordNotifier());
        if (activeTask.compareAndSet(null, task)) {
            task.start();
            logger.info("Scheduled restart task started. Next restart: "
                    + restartTime.format(DateTimeFormatter.RFC_1123_DATE_TIME)
                    + " (" + formatDuration(millisUntil) + " from now)"
                    + " via schedule '" + sourceName + "'");
        }
    }

    /**
     * Starts a manual (one-shot) restart task, replacing any existing scheduled task.
     * A running manual task is NOT replaced — the caller must cancel it first.
     *
     * @param millisUntil  milliseconds until restart
     * @param reason       human-readable reason
     * @param initiator    display name of the player/console who issued the command
     */
    public void startManualTask(long millisUntil, String reason, String initiator) {
        RestartTask existing = activeTask.get();
        if (existing != null && existing.isManual()) {
            // Manual task already running — caller should cancel first
            return;
        }
        if (existing != null) {
            // Cancel the scheduled task to make room for the manual one
            existing.cancelTask();
        }

        RestartTask task = new RestartTask(plugin, millisUntil, reason, initiator, "manual",
                getGracePeriodHandler(), getDiscordNotifier());
        activeTask.set(task);
        task.start();
        logger.info("Manual restart task started by " + initiator
                + " (" + formatDuration(millisUntil) + " from now). Reason: " + reason);
    }

    /**
     * Cancels the currently active manual restart task.
     *
     * @return {@code true} if a manual task was cancelled; {@code false} if there
     *         was nothing to cancel (or the active task was a scheduled one)
     */
    public boolean cancelManualTask() {
        RestartTask task = activeTask.get();
        if (task == null || !task.isManual()) {
            return false;
        }
        if (activeTask.compareAndSet(task, null)) {
            task.cancelTask();
            scheduleNext(); // Re-queue the next scheduled restart
            return true;
        }
        return false;
    }

    /**
     * Called by {@link RestartTask} when it completes (naturally or forcibly).
     * Clears the active task reference.
     */
    public void onTaskComplete(RestartTask task) {
        activeTask.compareAndSet(task, null);
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /**
     * Returns the active {@link RestartTask}, or {@code null} if none is running.
     */
    public RestartTask getActiveTask() {
        return activeTask.get();
    }

    /**
     * Returns an unmodifiable snapshot of the loaded schedules.
     */
    public List<RestartSchedule> getSchedules() {
        lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(schedules));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds the earliest next restart time across all enabled schedules.
     *
     * <p>When two schedules have a next restart within {@value #TIEBREAK_WINDOW_MILLIS}ms
     * of each other, the one with the <em>lowest priority number</em> wins
     * (priority 1 = highest priority).</p>
     *
     * @return the winning {@link ZonedDateTime}, or empty if no schedules are enabled
     */
    public Optional<ZonedDateTime> findNextRestart() {
        return getNextRestartWithSchedule().map(RestartWithSchedule::restartTime);
    }

    /**
     * Finds the earliest next restart and returns it paired with the
     * {@link RestartSchedule} that owns it.
     *
     * <p>Priority tiebreak: if two schedules' next restart times are within
     * {@value #TIEBREAK_WINDOW_MILLIS}ms of each other, the one with the
     * lowest {@link RestartSchedule#getPriority()} number wins.</p>
     *
     * @return a {@link RestartWithSchedule} record, or empty if no schedules are active
     */
    public Optional<RestartWithSchedule> getNextRestartWithSchedule() {
        lock.readLock().lock();
        try {
            ZonedDateTime   best         = null;
            RestartSchedule bestSchedule = null;

            for (RestartSchedule sched : schedules) {
                Optional<ZonedDateTime> next = sched.getNextRestart();
                if (next.isEmpty()) continue;

                ZonedDateTime candidate = next.get();

                if (best == null) {
                    best         = candidate;
                    bestSchedule = sched;
                    continue;
                }

                long diffMillis = Math.abs(
                        candidate.toInstant().toEpochMilli() - best.toInstant().toEpochMilli());

                if (diffMillis <= TIEBREAK_WINDOW_MILLIS) {
                    // Tie — lowest priority number wins (1 = highest priority)
                    if (sched.getPriority() < bestSchedule.getPriority()) {
                        best         = candidate;
                        bestSchedule = sched;
                    }
                } else if (candidate.isBefore(best)) {
                    // Genuinely earlier — always prefer it
                    best         = candidate;
                    bestSchedule = sched;
                }
            }

            if (best == null) return Optional.empty();
            return Optional.of(new RestartWithSchedule(best, bestSchedule));
        } finally {
            lock.readLock().unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Parsing helpers
    // -------------------------------------------------------------------------

    private List<RestartSchedule> loadSchedules() {
        plugin.getConfigManager().reloadSchedules();
        var schedulesConfig = plugin.getConfigManager().getSchedulesConfig();

        var section = schedulesConfig.getConfigurationSection("schedules");
        if (section == null) {
            logger.warning("schedules.yml has no 'schedules' section — no schedules loaded.");
            return new ArrayList<>();
        }

        List<RestartSchedule> result = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) continue;

            RestartSchedule sched = parseSchedule(key, entry);
            if (sched != null) {
                result.add(sched);
                logger.info("Loaded schedule '" + key + "': " + sched);
            }
        }
        return result;
    }

    private RestartSchedule parseSchedule(String name, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);

        // Timezone
        String tzStr = section.getString("timezone", "UTC");
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tzStr);
        } catch (Exception e) {
            logger.warning("Schedule '" + name + "': invalid timezone '" + tzStr + "', defaulting to UTC.");
            zoneId = ZoneId.of("UTC");
        }

        // Days
        List<String> dayStrings = section.getStringList("days");
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String d : dayStrings) {
            try {
                days.add(DayOfWeek.valueOf(d.toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warning("Schedule '" + name + "': unknown day '" + d + "' — skipped.");
            }
        }
        if (days.isEmpty()) {
            logger.warning("Schedule '" + name + "' has no valid days — skipped.");
            return null;
        }

        // Times — supports "3 AM", "3:30 PM", or "15:00"
        List<String> timeStrings = section.getStringList("times");
        List<LocalTime> times = new ArrayList<>();
        for (String t : timeStrings) {
            LocalTime parsed = parseTime(t);
            if (parsed != null) {
                times.add(parsed);
            } else {
                logger.warning("Schedule '" + name + "': invalid time '" + t
                        + "' — expected formats: '3 AM', '3:30 PM', '15:00'. Skipped.");
            }
        }
        if (times.isEmpty()) {
            logger.warning("Schedule '" + name + "' has no valid times — skipped.");
            return null;
        }

        int priority = section.getInt("priority", 1);

        return new RestartSchedule(name, enabled, zoneId, days, times, priority);
    }

    /**
     * Parses a time string in any supported format:
     * <ul>
     *   <li>{@code "3 AM"} / {@code "12 PM"} — 12-hour, no minutes</li>
     *   <li>{@code "3:30 PM"} / {@code "12:00 AM"} — 12-hour with minutes</li>
     *   <li>{@code "15:00"} / {@code "03:00"} — 24-hour</li>
     * </ul>
     *
     * @return the parsed {@link LocalTime}, or {@code null} if no format matched
     */
    private static LocalTime parseTime(String raw) {
        String s = raw.trim().toUpperCase(Locale.ENGLISH);
        for (DateTimeFormatter fmt : TIME_FORMATS) {
            try {
                return LocalTime.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Display helpers
    // -------------------------------------------------------------------------

    /**
     * Formats a millisecond duration into a human-readable string like
     * "2h 15m 30s".
     */
    public static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours   = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0)   sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        sb.append(seconds).append("s");
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Record: restart time + owning schedule
    // -------------------------------------------------------------------------

    /**
     * Pairs a restart instant with the {@link RestartSchedule} that produces it.
     * Returned by {@link #getNextRestartWithSchedule()}.
     */
    public record RestartWithSchedule(ZonedDateTime restartTime, RestartSchedule schedule) {}
}
