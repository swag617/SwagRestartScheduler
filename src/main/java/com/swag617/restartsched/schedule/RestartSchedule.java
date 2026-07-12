package com.swag617.restartsched.schedule;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable data class representing a named restart schedule.
 *
 * <p>A schedule defines:</p>
 * <ul>
 *   <li>Which days of the week it is active ({@code days})</li>
 *   <li>What times during those days restarts occur ({@code times})</li>
 *   <li>The timezone for those times ({@code timezone})</li>
 *   <li>A {@code priority} used as a tiebreaker when two schedules share the same
 *       next restart instant</li>
 * </ul>
 *
 * <p>Use {@link #getNextRestart()} to retrieve the next wall-clock instant for this
 * schedule, or {@link Optional#empty()} if the schedule is disabled or misconfigured.</p>
 */
public final class RestartSchedule {

    private final String     name;
    private final boolean    enabled;
    private final ZoneId     timezone;
    private final Set<DayOfWeek> days;
    private final List<LocalTime> times;
    private final int        priority;

    public RestartSchedule(String name, boolean enabled, ZoneId timezone,
                           Set<DayOfWeek> days, List<LocalTime> times, int priority) {
        this.name     = name;
        this.enabled  = enabled;
        this.timezone = timezone;
        this.days     = Collections.unmodifiableSet(days);
        this.times    = Collections.unmodifiableList(times);
        this.priority = priority;
    }

    // -------------------------------------------------------------------------
    // Core logic
    // -------------------------------------------------------------------------

    /**
     * Calculates the next restart instant for this schedule relative to "now".
     *
     * <p>The search window is a rolling 8-day lookahead (7 days covers every
     * day-of-week once; the extra day handles the edge case where today has no
     * eligible time left).</p>
     *
     * @return the next {@link ZonedDateTime} in the schedule's timezone,
     *         or {@link Optional#empty()} if disabled / no days-times configured
     */
    public Optional<ZonedDateTime> getNextRestart() {
        if (!enabled || days.isEmpty() || times.isEmpty()) {
            return Optional.empty();
        }

        ZonedDateTime now = ZonedDateTime.now(timezone);
        ZonedDateTime candidate = null;

        // Search up to 8 days ahead
        for (int dayOffset = 0; dayOffset <= 7; dayOffset++) {
            ZonedDateTime day = now.plusDays(dayOffset).toLocalDate().atStartOfDay(timezone);

            if (!days.contains(day.getDayOfWeek())) {
                continue;
            }

            for (LocalTime time : times) {
                ZonedDateTime restart = day.with(time);
                // Must be strictly in the future (at least 1 second from now)
                if (restart.isAfter(now.plusSeconds(1))) {
                    if (candidate == null || restart.isBefore(candidate)) {
                        candidate = restart;
                    }
                }
            }

            // Once we found at least one candidate on this day, no need to go further
            // (times are not guaranteed sorted, but we already take the earliest above)
            if (candidate != null && dayOffset == 0) {
                // Today has a valid time — but keep scanning the day's remaining times
                // (handled by inner loop above), then break
                break;
            }
            if (candidate != null) {
                // We found a future day with a restart; since we iterate days in order
                // and take the minimum time per day, this is the earliest possible.
                break;
            }
        }

        return Optional.ofNullable(candidate);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ZoneId getTimezone() {
        return timezone;
    }

    public Set<DayOfWeek> getDays() {
        return days;
    }

    public List<LocalTime> getTimes() {
        return times;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public String toString() {
        return "RestartSchedule{name='" + name + '\''
                + ", enabled=" + enabled
                + ", timezone=" + timezone
                + ", days=" + days
                + ", times=" + times
                + ", priority=" + priority
                + '}';
    }
}
