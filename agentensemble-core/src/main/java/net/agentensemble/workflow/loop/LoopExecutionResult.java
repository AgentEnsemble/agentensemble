package net.agentensemble.workflow.loop;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;

/**
 * Result of executing a {@link Loop}.
 *
 * <p>Captures everything the outer ensemble (and trace/viz) needs to know:
 * <ul>
 *   <li>{@link #iterationsRun} -- how many full body iterations actually ran.</li>
 *   <li>{@link #terminationReason} -- why the loop stopped: {@code "predicate"} or
 *       {@code "maxIterations"}.</li>
 *   <li>{@link #history} -- per-iteration body outputs, keyed by body-task name (or
 *       description fallback when unnamed). Outer list is iteration-ordered.</li>
 *   <li>{@link #projectedOutputs} -- outputs as they appear to the outer ensemble,
 *       keyed by the original {@link Task} instances from {@code Loop.getBody()},
 *       projected per the loop's {@link LoopOutputMode}.</li>
 * </ul>
 */
@Value
public class LoopExecutionResult {

    /** The loop that produced this result. */
    Loop loop;

    /** Number of body iterations completed. {@code >= 1} on any successful execution. */
    int iterationsRun;

    /** Why the loop stopped: {@code "predicate"} or {@code "maxIterations"}. */
    String terminationReason;

    /**
     * Per-iteration body outputs. Outer list is iteration-ordered (index 0 = iteration 1).
     * Inner map is keyed by body-task name (or description if name is null), value is the
     * {@link TaskOutput} produced for that task in that iteration. Insertion order matches
     * body declaration order.
     */
    List<Map<String, TaskOutput>> history;

    /**
     * Outputs as they appear to the outer ensemble, keyed by the original {@link Task}
     * instances from {@code Loop.getBody()} (NOT the per-iteration rebuilt instances).
     * Projection rules per {@link LoopOutputMode}:
     * <ul>
     *   <li>{@link LoopOutputMode#LAST_ITERATION} -- one entry per body task with the
     *       last iteration's output for that task.</li>
     *   <li>{@link LoopOutputMode#FINAL_TASK_ONLY} -- one entry containing only the last
     *       body task's last-iteration output.</li>
     *   <li>{@link LoopOutputMode#ALL_ITERATIONS} -- one entry per body task; the entry's
     *       {@code raw} text is a newline-delimited concatenation of every iteration.</li>
     * </ul>
     *
     * <p>Identity-keyed so the outer scheduler can resolve the loop's contribution to
     * downstream tasks via the same {@code Map<Task, TaskOutput>} machinery used for
     * regular tasks.
     */
    IdentityHashMap<Task, TaskOutput> projectedOutputs;

    /**
     * Convenience: did the loop terminate via the predicate (vs hitting max-iterations)?
     */
    public boolean stoppedByPredicate() {
        return "predicate".equals(terminationReason);
    }

    /**
     * Convenience: did the loop hit its iteration cap without the predicate firing?
     */
    public boolean stoppedByMaxIterations() {
        return "maxIterations".equals(terminationReason);
    }

    /**
     * The last iteration's outputs, keyed by body-task name. Defensive copy.
     */
    public Map<String, TaskOutput> lastIterationOutputs() {
        if (history.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(history.get(history.size() - 1));
    }
}
