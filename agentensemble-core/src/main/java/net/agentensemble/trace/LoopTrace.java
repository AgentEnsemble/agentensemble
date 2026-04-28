package net.agentensemble.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * Per-loop execution trace, captured for every {@link net.agentensemble.workflow.loop.Loop}
 * that ran during an ensemble execution.
 *
 * <p>Recorded as a parallel side channel to {@link ExecutionTrace#taskTraces} so trace
 * consumers that don't care about loops can ignore the new {@link ExecutionTrace#loopTraces}
 * collection without changes.
 *
 * <p>The schema:
 * <ul>
 *   <li>{@link #loopName} -- the loop's name as configured on {@code Loop.builder().name(...)}.</li>
 *   <li>{@link #iterationsRun} -- how many full body iterations actually executed.</li>
 *   <li>{@link #maxIterations} -- the configured cap.</li>
 *   <li>{@link #terminationReason} -- {@code "predicate"} or {@code "maxIterations"}.</li>
 *   <li>{@link #onMaxIterations} -- the {@code MaxIterationsAction} configured.</li>
 *   <li>{@link #outputMode} -- the projection mode used for the loop's outer-DAG outputs.</li>
 *   <li>{@link #memoryMode} -- {@code "ACCUMULATE"} or {@code "FRESH_PER_ITERATION"}.</li>
 *   <li>{@link #iterations} -- per-iteration body task names that ran. Outer list is
 *       iteration-ordered (index 0 = iteration 1); inner list is body-declaration order
 *       of task names.</li>
 * </ul>
 *
 * <p>Per-iteration {@link TaskTrace}s for each body-task execution are NOT duplicated here --
 * they appear in the parent {@link ExecutionTrace#taskTraces} list as one trace per
 * (iteration, body task) pair. Use the iteration name list here as a key into the flat
 * task trace list when reconstructing per-iteration views.
 */
@Builder(toBuilder = true)
@Value
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class LoopTrace {

    /** The loop's name from {@code Loop.builder().name(...)}. */
    @NonNull
    String loopName;

    /** Number of body iterations that actually ran. {@code >= 1} for any executed loop. */
    int iterationsRun;

    /** The configured {@code maxIterations} cap. */
    int maxIterations;

    /** Why the loop stopped: {@code "predicate"} or {@code "maxIterations"}. */
    @NonNull
    String terminationReason;

    /**
     * Action taken when the {@code maxIterations} cap was hit.
     * String form of {@code MaxIterationsAction}: {@code "RETURN_LAST"}, {@code "THROW"},
     * or {@code "RETURN_WITH_FLAG"}.
     */
    @NonNull
    String onMaxIterations;

    /**
     * Projection mode for the loop's outer-DAG outputs.
     * String form of {@code LoopOutputMode}.
     */
    @NonNull
    String outputMode;

    /**
     * Memory propagation across iterations.
     * String form of {@code LoopMemoryMode}.
     */
    @NonNull
    String memoryMode;

    /**
     * Per-iteration body-task names that ran. Outer list is iteration-ordered;
     * inner list is body-declaration order of {@code Task.name} (or description fallback
     * when the body task has no name). Empty if the loop produced no iterations.
     */
    @Singular("iteration")
    List<List<String>> iterations;
}
