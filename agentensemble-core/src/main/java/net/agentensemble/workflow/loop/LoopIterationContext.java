package net.agentensemble.workflow.loop;

import java.util.List;
import java.util.Map;
import net.agentensemble.task.TaskOutput;

/**
 * View of loop state passed to a {@link LoopPredicate} after each iteration.
 *
 * <p>Outputs are keyed by body-task name (not identity) because tasks are rebuilt
 * each iteration via {@code Task.withRevisionFeedback(...)} when feedback injection
 * is enabled, so identity is not stable.
 *
 * <p>The predicate sees only loop-local context -- by construction it cannot reach
 * tasks outside the loop body. Predicates that need outer-DAG context should be
 * expressed as a body task that consumes that context, then have the predicate
 * inspect that body task's output.
 */
public interface LoopIterationContext {

    /**
     * 1-based iteration counter. The first time the predicate is evaluated,
     * {@code iterationNumber()} returns {@code 1}.
     */
    int iterationNumber();

    /**
     * Outputs of the body tasks from the iteration that just completed,
     * keyed by {@code Task.name}. If a body task has no name (null), the entry
     * is keyed by the task's description -- the predicate is responsible for
     * knowing how its loop body is named.
     */
    Map<String, TaskOutput> lastIterationOutputs();

    /**
     * Outputs from every iteration so far, including the one that just completed.
     * The outermost list is iteration-ordered (index 0 = iteration 1).
     */
    List<Map<String, TaskOutput>> history();

    /**
     * Convenience accessor: the {@code TaskOutput} of the last task in the body for
     * the iteration that just completed. Equivalent to retrieving the last entry of
     * {@link #lastIterationOutputs()} in body-declaration order.
     */
    TaskOutput lastBodyOutput();
}
