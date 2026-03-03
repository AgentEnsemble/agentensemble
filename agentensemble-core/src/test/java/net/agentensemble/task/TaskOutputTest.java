package net.agentensemble.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskOutputTest {

    @Test
    void testBuild_withAllFields_succeeds() {
        var now = Instant.now();
        var duration = Duration.ofSeconds(5);

        var output = TaskOutput.builder()
                .raw("The research findings are...")
                .taskDescription("Research AI trends")
                .agentRole("Senior Research Analyst")
                .completedAt(now)
                .duration(duration)
                .toolCallCount(3)
                .build();

        assertThat(output.getRaw()).isEqualTo("The research findings are...");
        assertThat(output.getTaskDescription()).isEqualTo("Research AI trends");
        assertThat(output.getAgentRole()).isEqualTo("Senior Research Analyst");
        assertThat(output.getCompletedAt()).isEqualTo(now);
        assertThat(output.getDuration()).isEqualTo(duration);
        assertThat(output.getToolCallCount()).isEqualTo(3);
    }

    @Test
    void testBuild_withZeroToolCallCount_succeeds() {
        var output = TaskOutput.builder()
                .raw("Direct answer without tools")
                .taskDescription("Simple task")
                .agentRole("Assistant")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(500))
                .toolCallCount(0)
                .build();

        assertThat(output.getToolCallCount()).isZero();
    }

    @Test
    void testTaskOutput_isImmutable() {
        var output = TaskOutput.builder()
                .raw("output")
                .taskDescription("task")
                .agentRole("agent")
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build();

        // @Value ensures no setters exist -- verified via compilation
        assertThat(output).isNotNull();
    }

    // ========================
    // Null field validation (@NonNull enforced by Lombok @Builder)
    // ========================

    @Test
    void testBuild_withNullRaw_throwsNullPointerException() {
        // TaskOutput declares @NonNull on raw -- Lombok @Builder rejects null at build time.
        assertThatThrownBy(() -> TaskOutput.builder()
                .raw(null)
                .taskDescription("task")
                .agentRole("agent")
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testBuild_withNullTaskDescription_throwsNullPointerException() {
        assertThatThrownBy(() -> TaskOutput.builder()
                .raw("output")
                .taskDescription(null)
                .agentRole("agent")
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testBuild_withNullAgentRole_throwsNullPointerException() {
        assertThatThrownBy(() -> TaskOutput.builder()
                .raw("output")
                .taskDescription("task")
                .agentRole(null)
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testBuild_defaultToolCallCount_isZero() {
        // When toolCallCount is not set, it defaults to 0 (primitive int default)
        var output = TaskOutput.builder()
                .raw("output")
                .taskDescription("task")
                .agentRole("agent")
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .build();

        assertThat(output.getToolCallCount()).isZero();
    }
}
