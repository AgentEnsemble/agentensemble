package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import net.agentensemble.web.RunState.Status;
import net.agentensemble.web.RunState.TaskOutputSnapshot;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RunState}: construction, accessors, status transitions, thread-safe
 * task output accumulation.
 */
class RunStateTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private RunState makeState(Status status) {
        return new RunState(
                "run-abc123",
                status,
                T0,
                Map.of("topic", "AI"),
                Map.of("triggeredBy", "ci"),
                3,
                "SEQUENTIAL",
                "session-42");
    }

    // ========================
    // Construction & immutable accessors
    // ========================

    @Test
    void construction_setsAllFields() {
        RunState state = makeState(Status.ACCEPTED);

        assertThat(state.getRunId()).isEqualTo("run-abc123");
        assertThat(state.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(state.getStartedAt()).isEqualTo(T0);
        assertThat(state.getInputs()).containsEntry("topic", "AI");
        assertThat(state.getTags()).containsEntry("triggeredBy", "ci");
        assertThat(state.getTaskCount()).isEqualTo(3);
        assertThat(state.getWorkflow()).isEqualTo("SEQUENTIAL");
        assertThat(state.getOriginSessionId()).isEqualTo("session-42");
    }

    @Test
    void construction_nullInputs_defaultsToEmptyMap() {
        RunState state = new RunState("r", Status.ACCEPTED, T0, null, null, 0, null, null);
        assertThat(state.getInputs()).isEmpty();
        assertThat(state.getTags()).isEmpty();
    }

    @Test
    void construction_completedAtIsNullInitially() {
        RunState state = makeState(Status.ACCEPTED);
        assertThat(state.getCompletedAt()).isNull();
    }

    @Test
    void construction_notCancelledInitially() {
        RunState state = makeState(Status.ACCEPTED);
        assertThat(state.isCancelled()).isFalse();
    }

    @Test
    void construction_errorIsNullInitially() {
        RunState state = makeState(Status.ACCEPTED);
        assertThat(state.getError()).isNull();
    }

    @Test
    void construction_taskOutputsEmptyInitially() {
        RunState state = makeState(Status.ACCEPTED);
        assertThat(state.getTaskOutputs()).isEmpty();
    }

    // ========================
    // Status transitions
    // ========================

    @Test
    void transitionTo_updatesStatus() {
        RunState state = makeState(Status.ACCEPTED);
        state.transitionTo(Status.RUNNING);
        assertThat(state.getStatus()).isEqualTo(Status.RUNNING);

        state.transitionTo(Status.COMPLETED);
        assertThat(state.getStatus()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void compareAndSetStatus_successWhenExpectedMatches() {
        RunState state = makeState(Status.ACCEPTED);
        boolean updated = state.compareAndSetStatus(Status.ACCEPTED, Status.RUNNING);
        assertThat(updated).isTrue();
        assertThat(state.getStatus()).isEqualTo(Status.RUNNING);
    }

    @Test
    void compareAndSetStatus_failsWhenExpectedDoesNotMatch() {
        RunState state = makeState(Status.ACCEPTED);
        boolean updated = state.compareAndSetStatus(Status.RUNNING, Status.COMPLETED);
        assertThat(updated).isFalse();
        assertThat(state.getStatus()).isEqualTo(Status.ACCEPTED);
    }

    // ========================
    // Mutable field updates
    // ========================

    @Test
    void setCompletedAt_updatesField() {
        RunState state = makeState(Status.COMPLETED);
        Instant completedAt = Instant.parse("2026-01-01T01:00:00Z");
        state.setCompletedAt(completedAt);
        assertThat(state.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void setWorkflow_updatesField() {
        RunState state = new RunState("r", Status.ACCEPTED, T0, null, null, 0, null, null);
        state.setWorkflow("PARALLEL");
        assertThat(state.getWorkflow()).isEqualTo("PARALLEL");
    }

    @Test
    void incrementCompletedTasks_incrementsAtomically() {
        RunState state = makeState(Status.RUNNING);
        assertThat(state.getCompletedTasks()).isZero();
        state.incrementCompletedTasks();
        state.incrementCompletedTasks();
        assertThat(state.getCompletedTasks()).isEqualTo(2);
    }

    @Test
    void cancel_flagsAsCancelled() {
        RunState state = makeState(Status.RUNNING);
        assertThat(state.isCancelled()).isFalse();
        state.cancel();
        assertThat(state.isCancelled()).isTrue();
    }

    @Test
    void setError_updatesErrorField() {
        RunState state = makeState(Status.FAILED);
        state.setError("LLM rate limit exceeded");
        assertThat(state.getError()).isEqualTo("LLM rate limit exceeded");
    }

    // ========================
    // Task output snapshots
    // ========================

    @Test
    void addTaskOutput_appendsToList() {
        RunState state = makeState(Status.COMPLETED);
        TaskOutputSnapshot snap1 = new TaskOutputSnapshot(null, "Research AI", "Result 1", 8200L, 10000L, 3);
        TaskOutputSnapshot snap2 = new TaskOutputSnapshot(null, "Write brief", "Result 2", 3100L, 5000L, 0);

        state.addTaskOutput(snap1);
        state.addTaskOutput(snap2);

        assertThat(state.getTaskOutputs()).hasSize(2);
        assertThat(state.getTaskOutputs().get(0)).isEqualTo(snap1);
        assertThat(state.getTaskOutputs().get(1)).isEqualTo(snap2);
    }

    @Test
    void getTaskOutputs_returnsUnmodifiableView() {
        RunState state = makeState(Status.COMPLETED);
        state.addTaskOutput(new TaskOutputSnapshot(null, "Task 1", "Output", null, -1L, 0));

        // The list returned is unmodifiable
        assertThat(state.getTaskOutputs()).hasSize(1);
    }
}
