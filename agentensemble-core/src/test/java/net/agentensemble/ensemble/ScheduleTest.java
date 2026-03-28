package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Schedule} and its sealed implementations.
 */
class ScheduleTest {

    @Test
    void every_positiveInterval_creates() {
        Schedule schedule = Schedule.every(Duration.ofSeconds(30));
        assertThat(schedule).isInstanceOf(Schedule.IntervalSchedule.class);
    }

    @Test
    void every_zeroInterval_throwsIAE() {
        assertThatThrownBy(() -> Schedule.every(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void every_negativeInterval_throwsIAE() {
        assertThatThrownBy(() -> Schedule.every(Duration.ofSeconds(-5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void every_nullInterval_throwsNPE() {
        assertThatThrownBy(() -> Schedule.every(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("interval");
    }

    @Test
    void cron_validExpression_creates() {
        Schedule schedule = Schedule.cron("0 * * * *");
        assertThat(schedule).isInstanceOf(Schedule.CronSchedule.class);
    }

    @Test
    void cron_blankExpression_throwsIAE() {
        assertThatThrownBy(() -> Schedule.cron("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void cron_nullExpression_throwsNPE() {
        assertThatThrownBy(() -> Schedule.cron(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expression");
    }

    @Test
    void intervalSchedule_accessors() {
        Duration interval = Duration.ofMinutes(5);
        Schedule.IntervalSchedule schedule = new Schedule.IntervalSchedule(interval);
        assertThat(schedule.interval()).isEqualTo(interval);
    }

    @Test
    void cronSchedule_accessors() {
        Schedule.CronSchedule schedule = new Schedule.CronSchedule("0 0 * * *");
        assertThat(schedule.expression()).isEqualTo("0 0 * * *");
    }
}
