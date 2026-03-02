package net.agentensemble.workflow;

import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;

import java.util.List;

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
     * @param resolvedTasks tasks to execute (with template variables already resolved)
     * @param verbose when true, elevates execution logging to INFO level
     * @return the combined ensemble output
     */
    EnsembleOutput execute(List<Task> resolvedTasks, boolean verbose);
}
