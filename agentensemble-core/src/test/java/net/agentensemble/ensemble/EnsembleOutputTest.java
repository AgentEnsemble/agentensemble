package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.agentensemble.Task;
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
    void testBuilder_withExitReasonTimeout_setsExitReason() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("partial result")
                .totalDuration(Duration.ofSeconds(1))
                .totalToolCalls(0)
                .exitReason(ExitReason.TIMEOUT)
                .build();
        assertThat(output.getExitReason()).isEqualTo(ExitReason.TIMEOUT);
    }

    @Test
    void testBuilder_withExitReasonError_setsExitReason() {
        TaskOutput taskOutput = taskOutputWithMetrics("Agent", TaskMetrics.EMPTY);
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("partial result")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ofSeconds(1))
                .totalToolCalls(0)
                .exitReason(ExitReason.ERROR)
                .build();
        assertThat(output.getExitReason()).isEqualTo(ExitReason.ERROR);
        assertThat(output.completedTasks()).hasSize(1);
    }

    @Test
    void exitReason_allFourValuesAreAccessible() {
        assertThat(ExitReason.COMPLETED).isNotNull();
        assertThat(ExitReason.USER_EXIT_EARLY).isNotNull();
        assertThat(ExitReason.TIMEOUT).isNotNull();
        assertThat(ExitReason.ERROR).isNotNull();
        assertThat(ExitReason.values()).hasSize(4);
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
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> output.getTaskOutputs().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // isComplete() tests
    // ========================

    @Test
    void isComplete_returnsTrueForCompleted() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("done")
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(ExitReason.COMPLETED)
                .build();
        assertThat(output.isComplete()).isTrue();
    }

    @Test
    void isComplete_returnsFalseForUserExitEarly() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("partial")
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(ExitReason.USER_EXIT_EARLY)
                .build();
        assertThat(output.isComplete()).isFalse();
    }

    @Test
    void isComplete_returnsFalseForTimeout() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("partial")
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(ExitReason.TIMEOUT)
                .build();
        assertThat(output.isComplete()).isFalse();
    }

    @Test
    void isComplete_returnsFalseForError() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("partial")
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(ExitReason.ERROR)
                .build();
        assertThat(output.isComplete()).isFalse();
    }

    // ========================
    // completedTasks() tests
    // ========================

    @Test
    void completedTasks_returnsSameListAsGetTaskOutputs() {
        TaskOutput t1 = taskOutputWithMetrics("Agent1", TaskMetrics.EMPTY);
        TaskOutput t2 = taskOutputWithMetrics("Agent2", TaskMetrics.EMPTY);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("final")
                .taskOutputs(List.of(t1, t2))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .build();

        assertThat(output.completedTasks()).isEqualTo(output.getTaskOutputs());
        assertThat(output.completedTasks()).hasSize(2);
    }

    @Test
    void completedTasks_isEmptyForPartialRunWithNoCompleted() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("")
                .taskOutputs(List.of())
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(ExitReason.USER_EXIT_EARLY)
                .build();

        assertThat(output.completedTasks()).isEmpty();
    }

    // ========================
    // lastCompletedOutput() tests
    // ========================

    @Test
    void lastCompletedOutput_returnsLastTaskOutput() {
        TaskOutput t1 = taskOutputWithMetrics("Agent1", TaskMetrics.EMPTY);
        TaskOutput t2 = taskOutputWithMetrics("Agent2", TaskMetrics.EMPTY);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("final")
                .taskOutputs(List.of(t1, t2))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .build();

        Optional<TaskOutput> last = output.lastCompletedOutput();
        assertThat(last).isPresent();
        assertThat(last.get()).isSameAs(t2);
    }

    @Test
    void lastCompletedOutput_returnsEmptyWhenNoTaskOutputs() {
        EnsembleOutput output = EnsembleOutput.builder()
                .raw("")
                .taskOutputs(List.of())
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .exitReason(ExitReason.USER_EXIT_EARLY)
                .build();

        assertThat(output.lastCompletedOutput()).isEmpty();
    }

    @Test
    void lastCompletedOutput_returnsSingleElementList() {
        TaskOutput t1 = taskOutputWithMetrics("Agent1", TaskMetrics.EMPTY);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .taskOutputs(List.of(t1))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .build();

        assertThat(output.lastCompletedOutput()).isPresent().containsSame(t1);
    }

    // ========================
    // getOutput(Task) tests
    // ========================

    @Test
    void getOutput_returnsOutputForKnownTask() {
        Task task = Task.of("Research AI trends");
        TaskOutput taskOutput = taskOutputWithMetrics("Researcher", TaskMetrics.EMPTY);

        java.util.Map<Task, TaskOutput> index = new java.util.IdentityHashMap<>();
        index.put(task, taskOutput);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("Research AI trends result")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .taskOutputIndex(index)
                .build();

        assertThat(output.getOutput(task)).isPresent().containsSame(taskOutput);
    }

    @Test
    void getOutput_returnsEmptyForUnknownTask() {
        Task knownTask = Task.of("Known task");
        Task unknownTask = Task.of("Unknown task");
        TaskOutput taskOutput = taskOutputWithMetrics("Agent", TaskMetrics.EMPTY);

        java.util.Map<Task, TaskOutput> index = new java.util.IdentityHashMap<>();
        index.put(knownTask, taskOutput);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .taskOutputIndex(index)
                .build();

        assertThat(output.getOutput(unknownTask)).isEmpty();
    }

    @Test
    void getOutput_returnsEmptyWhenIndexNotProvided() {
        Task task = Task.of("Some task");
        TaskOutput taskOutput = taskOutputWithMetrics("Agent", TaskMetrics.EMPTY);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .build(); // no taskOutputIndex

        assertThat(output.getOutput(task)).isEmpty();
    }

    @Test
    void getOutput_usesIdentityNotEquality() {
        // Two tasks with identical field values but different instances
        Task taskA = Task.of("Research AI trends");
        Task taskB = Task.of("Research AI trends"); // equal fields, different instance
        TaskOutput outputA = taskOutputWithMetrics("AgentA", TaskMetrics.EMPTY);

        java.util.Map<Task, TaskOutput> index = new java.util.IdentityHashMap<>();
        index.put(taskA, outputA);

        EnsembleOutput output = EnsembleOutput.builder()
                .raw("result")
                .taskOutputs(List.of(outputA))
                .totalDuration(Duration.ZERO)
                .totalToolCalls(0)
                .taskOutputIndex(index)
                .build();

        assertThat(output.getOutput(taskA)).isPresent(); // same instance -> found
        assertThat(output.getOutput(taskB)).isEmpty(); // different instance -> not found
    }

    @Test
    void getOutput_returnsEmptyForNullTask() {
        EnsembleOutput output = EnsembleOutput.of("result", List.of(), Duration.ZERO, 0);
        assertThat(output.getOutput(null)).isEmpty();
    }

    private static TaskOutput taskOutputWithMetrics(String agentRole, TaskMetrics metrics) {
        TaskOutput output = mock(TaskOutput.class);
        when(output.getAgentRole()).thenReturn(agentRole);
        when(output.getMetrics()).thenReturn(metrics);
        when(output.getTrace()).thenReturn(null);
        return output;
    }
}
