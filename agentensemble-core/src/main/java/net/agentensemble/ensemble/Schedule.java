package net.agentensemble.ensemble;

import java.time.Duration;
import java.util.Objects;

/**
 * Defines when a {@link ScheduledTask} should fire.
 *
 * <p>Two schedule types are supported:
 * <ul>
 *   <li>{@link IntervalSchedule} -- fires at a fixed interval</li>
 *   <li>{@link CronSchedule} -- fires according to a cron expression</li>
 * </ul>
 *
 * @see ScheduledTask
 */
public sealed interface Schedule {

    /**
     * Create an interval-based schedule.
     *
     * @param interval the interval between firings; must be positive
     * @return a new interval schedule
     */
    static Schedule every(Duration interval) {
        return new IntervalSchedule(interval);
    }

    /**
     * Create a cron-based schedule.
     *
     * @param expression the cron expression; must not be blank
     * @return a new cron schedule
     */
    static Schedule cron(String expression) {
        return new CronSchedule(expression);
    }

    /**
     * Interval-based schedule that fires at a fixed rate.
     */
    record IntervalSchedule(Duration interval) implements Schedule {
        public IntervalSchedule {
            Objects.requireNonNull(interval, "interval must not be null");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("interval must be positive; got: " + interval);
            }
        }
    }

    /**
     * Cron-based schedule that fires according to a cron expression.
     *
     * <p>The expression format follows standard 5-field cron syntax (minute, hour,
     * day-of-month, month, day-of-week).
     */
    record CronSchedule(String expression) implements Schedule {
        public CronSchedule {
            Objects.requireNonNull(expression, "expression must not be null");
            if (expression.isBlank()) {
                throw new IllegalArgumentException("expression must not be blank");
            }
        }
    }
}
