package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ParallelWorkflowExecutor error handling strategies.
 *
 * Covers FAIL_FAST and CONTINUE_ON_ERROR behaviour, including skip cascading
 * and transitive dependent skipping.
 */
class ParallelWorkflowExecutorErrorTest extends ParallelWorkflowExecutorTestBase {

    // ========================
    // FAIL_FAST error handling
    // ========================

    @Test
    void testFailFast_oneTaskFails_throwsTaskExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = task("Good task", good);
        var tBad = task("Bad task", bad);

        assertThatThrownBy(() -> executor(ParallelErrorStrategy.FAIL_FAST)
                        .execute(List.of(tGood, tBad), ExecutionContext.disabled()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var te = (TaskExecutionException) ex;
                    assertThat(te.getTaskDescription()).isEqualTo("Bad task");
                    assertThat(te.getAgentRole()).isEqualTo("Bad");
                });
    }

    @Test
    void testFailFast_singleTask_throwsTaskExecutionException() {
        var bad = agentThatFails("Bad");
        var tBad = task("Failing task", bad);

        assertThatThrownBy(() ->
                        executor(ParallelErrorStrategy.FAIL_FAST).execute(List.of(tBad), ExecutionContext.disabled()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    var te = (TaskExecutionException) ex;
                    assertThat(te.getTaskDescription()).isEqualTo("Failing task");
                });
    }

    // ========================
    // CONTINUE_ON_ERROR error handling
    // ========================

    @Test
    void testContinueOnError_allSucceed_returnsNormally() {
        var a = agentWithResponse("A", "A result");
        var b = agentWithResponse("B", "B result");
        var ta = task("Task A", a);
        var tb = task("Task B", b);

        var output =
                executor(ParallelErrorStrategy.CONTINUE_ON_ERROR).execute(List.of(ta, tb), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void testContinueOnError_oneTaskFails_throwsParallelExecutionException() {
        var good = agentWithResponse("Good", "Good result");
        var bad = agentThatFails("Bad");
        var tGood = task("Good task", good);
        var tBad = task("Bad task", bad);

        assertThatThrownBy(() -> executor(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .execute(List.of(tGood, tBad), ExecutionContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getFailedCount()).isEqualTo(1);
                    assertThat(pe.getFailedTaskCauses()).containsKey("Bad task");
                    assertThat(pe.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(pe.getCompletedTaskOutputs().get(0).getAgentRole())
                            .isEqualTo("Good");
                });
    }

    @Test
    void testContinueOnError_dependentOfFailed_isSkipped() {
        // A succeeds, B fails, C depends on B -- C must be skipped
        var a = agentWithResponse("A", "A result");
        var bad = agentThatFails("Bad");
        var skip = agentWithResponse("Skip", "Should not execute");
        var ta = task("Task A", a);
        var tBad = task("Task Bad", bad);
        var tSkip = taskWithContext("Task Skip", skip, List.of(tBad));

        assertThatThrownBy(() -> executor(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .execute(List.of(ta, tBad, tSkip), ExecutionContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(pe.getCompletedTaskOutputs().get(0).getAgentRole())
                            .isEqualTo("A");
                    assertThat(pe.getFailedTaskCauses()).containsKey("Task Bad");
                    assertThat(pe.getFailedTaskCauses()).doesNotContainKey("Task Skip");
                });
    }

    @Test
    void testContinueOnError_transitiveDependentOfFailed_isSkipped() {
        // Root fails, Middle depends on Root, Tail depends on Middle.
        // Both Middle and Tail must be skipped.
        var badRoot = agentThatFails("Root");
        var middle = agentWithResponse("Middle", "Middle result");
        var tail = agentWithResponse("Tail", "Tail result");
        var tRoot = task("Task Root", badRoot);
        var tMiddle = taskWithContext("Task Middle", middle, List.of(tRoot));
        var tTail = taskWithContext("Task Tail", tail, List.of(tMiddle));

        assertThatThrownBy(() -> executor(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .execute(List.of(tRoot, tMiddle, tTail), ExecutionContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getFailedCount()).isEqualTo(1);
                    assertThat(pe.getFailedTaskCauses()).containsKey("Task Root");
                    assertThat(pe.getFailedTaskCauses()).doesNotContainKeys("Task Middle", "Task Tail");
                    assertThat(pe.getCompletedTaskOutputs()).isEmpty();
                });
    }

    @Test
    void testContinueOnError_multipleFailures_allReported() {
        var bad1 = agentThatFails("Bad1");
        var bad2 = agentThatFails("Bad2");
        var t1 = task("Fail 1", bad1);
        var t2 = task("Fail 2", bad2);

        assertThatThrownBy(() -> executor(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .execute(List.of(t1, t2), ExecutionContext.disabled()))
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    var pe = (ParallelExecutionException) ex;
                    assertThat(pe.getFailedCount()).isEqualTo(2);
                    assertThat(pe.getFailedTaskCauses()).containsKeys("Fail 1", "Fail 2");
                    assertThat(pe.getCompletedTaskOutputs()).isEmpty();
                });
    }
}
