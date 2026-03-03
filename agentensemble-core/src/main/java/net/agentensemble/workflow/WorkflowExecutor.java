package net.agentensemble.workflow;

import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.memory.MemoryContext;

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
     * @param verbose       when true, elevates execution logging to INFO level
     * @param memoryContext runtime memory state for this run; use
     *                      {@link MemoryContext#disabled()} when memory is not configured
     * @return the combined ensemble output
     */
    EnsembleOutput execute(List<Task> resolvedTasks, boolean verbose, MemoryContext memoryContext);
}
