package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ScheduledTask}.
 */
class ScheduledTaskTest {

    private final Task sampleTask = Task.of("Do something");
    private final Schedule sampleSchedule = Schedule.every(Duration.ofMinutes(1));

    @Test
    void builder_allFields() {
        ScheduledTask st = ScheduledTask.builder()
                .name("health-check")
                .task(sampleTask)
                .schedule(sampleSchedule)
                .broadcastTo("health-topic")
                .build();

        assertThat(st.name()).isEqualTo("health-check");
        assertThat(st.task()).isSameAs(sampleTask);
        assertThat(st.schedule()).isEqualTo(sampleSchedule);
        assertThat(st.broadcastTo()).isEqualTo("health-topic");
    }

    @Test
    void builder_withoutBroadcastTo() {
        ScheduledTask st = ScheduledTask.builder()
                .name("periodic-scan")
                .task(sampleTask)
                .schedule(sampleSchedule)
                .build();

        assertThat(st.name()).isEqualTo("periodic-scan");
        assertThat(st.broadcastTo()).isNull();
    }

    @Test
    void builder_nullName_throwsNPE() {
        assertThatThrownBy(() -> ScheduledTask.builder()
                        .name(null)
                        .task(sampleTask)
                        .schedule(sampleSchedule)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void builder_blankName_throwsIAE() {
        assertThatThrownBy(() -> ScheduledTask.builder()
                        .name("   ")
                        .task(sampleTask)
                        .schedule(sampleSchedule)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void builder_nullTask_throwsNPE() {
        assertThatThrownBy(() -> ScheduledTask.builder()
                        .name("test")
                        .task(null)
                        .schedule(sampleSchedule)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("task");
    }

    @Test
    void builder_nullSchedule_throwsNPE() {
        assertThatThrownBy(() -> ScheduledTask.builder()
                        .name("test")
                        .task(sampleTask)
                        .schedule(null)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("schedule");
    }

    @Test
    void record_accessors() {
        ScheduledTask st = new ScheduledTask("my-task", sampleTask, sampleSchedule, "my-topic");

        assertThat(st.name()).isEqualTo("my-task");
        assertThat(st.task()).isSameAs(sampleTask);
        assertThat(st.schedule()).isEqualTo(sampleSchedule);
        assertThat(st.broadcastTo()).isEqualTo("my-topic");
    }
}
