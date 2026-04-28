package net.agentensemble.workflow;

import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
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

    /**
     * Execute a heterogeneous list of {@link WorkflowNode}s (Tasks and Loops) and return
     * the combined output.
     *
     * <p>Default implementation: rejects any node that is not a {@link Task}. Implementations
     * that support {@link net.agentensemble.workflow.loop.Loop} (currently
     * {@link SequentialWorkflowExecutor} and {@link ParallelWorkflowExecutor}) override this.
     *
     * @param nodes            heterogeneous workflow nodes; each must be either a {@link Task}
     *                         or a {@link net.agentensemble.workflow.loop.Loop}
     * @param executionContext execution context shared with the outer ensemble run
     * @return the combined ensemble output, including loop history side channel
     * @throws ValidationException if a {@link net.agentensemble.workflow.loop.Loop} appears in
     *         {@code nodes} and this executor does not support loops
     */
    default EnsembleOutput executeNodes(List<WorkflowNode> nodes, ExecutionContext executionContext) {
        List<Task> tasks = new ArrayList<>(nodes.size());
        for (WorkflowNode node : nodes) {
            if (node instanceof Task t) {
                tasks.add(t);
            } else {
                throw new ValidationException(getClass().getSimpleName() + " does not support "
                        + node.getClass().getSimpleName()
                        + " nodes. Loops are supported only with Workflow.SEQUENTIAL or Workflow.PARALLEL.");
            }
        }
        return execute(tasks, executionContext);
    }
}
