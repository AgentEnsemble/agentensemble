package net.agentensemble.web;

import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.exception.ExitEarlyException;

/**
 * An {@link EnsembleListener} that checks the cancellation flag on every task start
 * and throws {@link ExitEarlyException} when the run has been flagged for cancellation.
 *
 * <p>This implements cooperative cancellation at task boundaries: a cancellation request
 * recorded in {@link RunState} by {@link RunManager#cancelRun(String)} will take effect
 * before the next task starts, allowing the current in-flight task to complete normally.
 *
 * <p>Thread safety: {@link RunState#isCancelled()} is a volatile read.
 */
final class CancellationCheckListener implements EnsembleListener {

    private final RunState runState;

    /**
     * Creates a listener that checks the cancelled flag on the given run state.
     *
     * @param runState the run state to inspect; must not be null
     */
    CancellationCheckListener(RunState runState) {
        this.runState = runState;
    }

    @Override
    public void onTaskStart(TaskStartEvent event) {
        if (runState.isCancelled()) {
            throw new ExitEarlyException("Run " + runState.getRunId() + " cancelled by API request");
        }
    }
}
