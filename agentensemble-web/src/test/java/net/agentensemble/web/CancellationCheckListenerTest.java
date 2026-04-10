package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.exception.ExitEarlyException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CancellationCheckListener}.
 */
class CancellationCheckListenerTest {

    private RunState buildState(boolean cancelled) {
        RunState state =
                new RunState("run-1", RunState.Status.ACCEPTED, java.time.Instant.now(), null, null, 3, null, null);
        if (cancelled) {
            state.cancel();
        }
        return state;
    }

    @Test
    void onTaskStart_notCancelled_doesNotThrow() {
        RunState state = buildState(false);
        CancellationCheckListener listener = new CancellationCheckListener(state);

        TaskStartEvent event = new TaskStartEvent("Test task", "researcher", 1, 3);
        assertThatCode(() -> listener.onTaskStart(event)).doesNotThrowAnyException();
    }

    @Test
    void onTaskStart_cancelled_throwsExitEarlyException() {
        RunState state = buildState(true);
        CancellationCheckListener listener = new CancellationCheckListener(state);

        TaskStartEvent event = new TaskStartEvent("Test task", "researcher", 1, 3);
        assertThatThrownBy(() -> listener.onTaskStart(event))
                .isInstanceOf(ExitEarlyException.class)
                .hasMessageContaining("run-1");
    }

    @Test
    void onTaskStart_cancelledAfterConstruction_throwsExitEarlyException() {
        RunState state = buildState(false);
        CancellationCheckListener listener = new CancellationCheckListener(state);

        // Cancel the run after the listener was created
        state.cancel();

        TaskStartEvent event = new TaskStartEvent("Test task", "researcher", 1, 3);
        assertThatThrownBy(() -> listener.onTaskStart(event)).isInstanceOf(ExitEarlyException.class);
    }
}
