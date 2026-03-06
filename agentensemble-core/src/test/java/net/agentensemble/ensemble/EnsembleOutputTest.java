package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/** Tests for EnsembleOutput construction, metrics auto-computation, and null safety. */
class EnsembleOutputTest {

    @Test
    void testOf_computesMetricsFromOutputs() {
        TaskMetrics tm = TaskMetrics.builder()
                .inputTokens(300L)
                .outputTokens(100L)
                .totalTokens(400L)
                .llmCallCount(1)
                .build();
        TaskOutput taskOutput = taskOutputWithMetrics("Researcher", tm);

        EnsembleOutput output = EnsembleOutput.of("Final answer", List.of(taskOutput), Duration.ofSeconds(5), 2);

        assertThat(output.getRaw()).isEqualTo("Final answer");
        assertThat(output.getTotalDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(output.getTotalToolCalls()).isEqualTo(2);
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getMetrics()).isNotNull();
        assertThat(output.getMetrics().getTotalInputTokens()).isEqualTo(300L);
    }

    @Test
    void testOf_emptyOutputs_returnsEmptyMetrics() {
        EnsembleOutput output = EnsembleOutput.of("", List.of(), Duration.ZERO, 0);
        assertThat(output.getMetrics()).isEqualTo(ExecutionMetrics.EMPTY);
    }

    @Test
    void testOf_nullOutputs_returnsEmptyMetrics() {
        EnsembleOutput output = EnsembleOutput.of("", null, Duration.ZERO, 0);
        assertThat(output.getTaskOutputs()).isEmpty();
        assertThat(output.getMetrics()).isEqualTo(ExecutionMetrics.EMPTY);
    }

    @Test
    void testOf_traceIsNullByDefault() {
        EnsembleOutput output = EnsembleOutput.of("", List.of(), Duration.ZERO, 0);
        assertThat(output.getTrace()).isNull();
    }

    @Test
    void testBuilder_withExplicitMetrics_usesProvidedMetrics() {
        ExecutionMetrics customMetrics = ExecutionMetrics.builder()
                .totalInputTokens(999L)
                .totalOutputTokens(1L)
                .build();

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("output")
                .totalDuration(Duration.ofSeconds(1))
                .totalToolCalls(0)
                .metrics(customMetrics)
                .build();

        assertThat(output.getMetrics().getTotalInputTokens()).isEqualTo(999L);
    }

    @Test
    void testBuilder_withoutMetrics_autoComputesFromOutputs() {
        TaskMetrics tm = TaskMetrics.builder()
                .inputTokens(100L)
                .outputTokens(50L)
                .totalTokens(150L)
                .build();
        TaskOutput taskOutput = taskOutputWithMetrics("Agent", tm);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ofSeconds(2))
                .totalToolCalls(0)
                .build();

        assertThat(output.getMetrics().getTotalInputTokens()).isEqualTo(100L);
    }

    @Test
    void testOf_exitReasonDefaultsToCompleted() {
        EnsembleOutput output = EnsembleOutput.of("result", List.of(), Duration.ZERO, 0);
        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
    }

    @Test
    void testBuilder_withExitReasonUserExitEarly_setsExitReason() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("partial result")
                .totalDuration(Duration.ofSeconds(1))
                .totalToolCalls(0)
                .exitReason(ExitReason.USER_EXIT_EARLY)
                .build();
        assertThat(output.getExitReason()).isEqualTo(ExitReason.USER_EXIT_EARLY);
    }

    @Test
    void testBuilder_withNullExitReason_defaultsToCompleted() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(null)
                .build();
        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
    }

    @Test
    void exitReason_allValuesAreAccessible() {
        // Ensure all ExitReason enum values are reachable
        assertThat(ExitReason.COMPLETED).isNotNull();
        assertThat(ExitReason.USER_EXIT_EARLY).isNotNull();
        assertThat(ExitReason.values()).hasSize(2);
    }

    @Test
    void testTaskOutputsList_isImmutable() {
        TaskOutput taskOutput = taskOutputWithMetrics("Agent", TaskMetrics.EMPTY);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .build();

        assertThat(output.getTaskOutputs()).hasSize(1);
        // Verify the returned list is truly unmodifiable
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> output.getTaskOutputs().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static TaskOutput taskOutputWithMetrics(String agentRole, TaskMetrics metrics) {
        TaskOutput output = mock(TaskOutput.class);
        when(output.getAgentRole()).thenReturn(agentRole);
        when(output.getMetrics()).thenReturn(metrics);
        when(output.getTrace()).thenReturn(null);
        return output;
    }
}
