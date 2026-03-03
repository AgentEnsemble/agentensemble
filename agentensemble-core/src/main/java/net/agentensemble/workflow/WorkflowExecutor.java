package net.agentensemble.workflow;

import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.execution.ExecutionContext;

/**
 * Strategy interface for executing ensemble tasks.
 *
 * Implementations define how tasks are orchestrated:
 * sequentially, hierarchically, in parallel, etc.
 */
public interface WorkflowExecutor {

    /**
     * Execute the given list of tasks and return the combined output.
     *
     * @param resolvedTasks    tasks to execute (with template variables already resolved)
     * @param executionContext execution context bundling memory state, verbose flag, and
     *                         event listeners for this run
     * @return the combined ensemble output
     */
    EnsembleOutput execute(List<Task> resolvedTasks, ExecutionContext executionContext);
}
