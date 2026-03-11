package net.agentensemble.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for TaskHandlerContext record construction and defensive copying.
 */
class TaskHandlerContextTest {

    // ========================
    // Build success cases
    // ========================

    @Test
    void testConstructor_withEmptyContextOutputs_succeeds() {
        var ctx = new TaskHandlerContext("Fetch data", "JSON response", List.of());

        assertThat(ctx.description()).isEqualTo("Fetch data");
        assertThat(ctx.expectedOutput()).isEqualTo("JSON response");
        assertThat(ctx.contextOutputs()).isEmpty();
    }

    @Test
    void testConstructor_withContextOutputs_succeeds() {
        var output = TaskOutput.builder()
                .raw("prior result")
                .taskDescription("Prior task")
                .agentRole("(deterministic)")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(10))
                .toolCallCount(0)
                .build();

        var ctx = new TaskHandlerContext("Summarize", "A summary", List.of(output));

        assertThat(ctx.contextOutputs()).hasSize(1);
        assertThat(ctx.contextOutputs().get(0).getRaw()).isEqualTo("prior result");
    }

    // ========================
    // Defensive copy
    // ========================

    @Test
    void testContextOutputs_isDefensivelyCopied() {
        var output = TaskOutput.builder()
                .raw("data")
                .taskDescription("Task A")
                .agentRole("(deterministic)")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(5))
                .toolCallCount(0)
                .build();

        var ctx = new TaskHandlerContext("Desc", "Output", List.of(output));

        assertThat(ctx.contextOutputs()).isUnmodifiable();
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void testConstructor_withNullDescription_throwsNPE() {
        assertThatThrownBy(() -> new TaskHandlerContext(null, "expected", List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("description");
    }

    @Test
    void testConstructor_withNullExpectedOutput_throwsNPE() {
        assertThatThrownBy(() -> new TaskHandlerContext("desc", null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expectedOutput");
    }

    @Test
    void testConstructor_withNullContextOutputs_throwsNPE() {
        assertThatThrownBy(() -> new TaskHandlerContext("desc", "expected", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("contextOutputs");
    }
}
