package net.agentensemble.workflow.loop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.WorkflowNode;

/**
 * A bounded loop over a sub-ensemble of tasks.
 *
 * <p>The body executes in declared order. After each iteration, the configured
 * {@link LoopPredicate} is evaluated; if it returns {@code true}, the loop stops
 * and emits its final outputs per {@link LoopOutputMode}. Otherwise, the body
 * runs again, up to {@code maxIterations}.
 *
 * <p>Loops appear as a single super-node in the outer DAG. Their {@code context}
 * dependencies (analogous to {@code Task.context}) determine when the loop starts
 * relative to other workflow nodes.
 *
 * <p>Reflection example:
 * <pre>{@code
 * Loop reflection = Loop.builder()
 *     .name("reflection")
 *     .task(writeTask)
 *     .task(critiqueTask)
 *     .until(ctx -> ctx.lastBodyOutput().getRaw().contains("APPROVED"))
 *     .maxIterations(5)
 *     .build();
 *
 * Ensemble.builder()
 *     .task(researchTask)
 *     .loop(reflection)
 *     .task(publishTask)
 *     .build()
 *     .run();
 * }</pre>
 *
 * <p>Defaults: {@code maxIterations=5}, {@code onMaxIterations=RETURN_LAST},
 * {@code outputMode=LAST_ITERATION}, {@code memoryMode=ACCUMULATE},
 * {@code injectFeedback=true}, sequential body. At least one stop condition
 * (predicate or {@code maxIterations}) must be set; both are recommended.
 */
@Builder(toBuilder = true)
@Value
public class Loop implements WorkflowNode {

    /** Default {@link #maxIterations} when not explicitly set on the builder. */
    public static final int DEFAULT_MAX_ITERATIONS = 5;

    /**
     * Logical name for this loop. Used in trace, viz, error messages, and as the
     * key in {@code EnsembleOutput.getLoopHistory(...)}.
     *
     * <p>Required and must be non-blank.
     */
    String name;

    /**
     * Tasks executed in declared order on each iteration. Body tasks may declare
     * {@code context()} only on other tasks within the same body. Cross-loop
     * dependencies must be declared on the {@link Loop} itself via
     * {@link LoopBuilder#context(Task)}.
     *
     * <p>Required and must be non-empty. Body must not contain another {@link Loop}
     * (nested loops are deferred to a future version).
     */
    @Singular("task")
    List<Task> body;

    /**
     * Stop predicate evaluated after each iteration. May be {@code null} only when
     * {@link #maxIterations} is set; in that case the loop always runs exactly
     * {@code maxIterations} times.
     */
    LoopPredicate until;

    /**
     * Maximum number of iterations. Hard cap regardless of predicate. Must be
     * &ge; 1. Default {@value #DEFAULT_MAX_ITERATIONS}.
     */
    int maxIterations;

    /** What to do when {@link #maxIterations} is reached without the predicate firing. */
    MaxIterationsAction onMaxIterations;

    /** How the loop's outputs are projected to the outer ensemble after termination. */
    LoopOutputMode outputMode;

    /** Whether body memory accumulates across iterations or is cleared between them. */
    LoopMemoryMode memoryMode;

    /**
     * For {@link LoopMemoryMode#WINDOW}: the number of most-recent memory entries to keep
     * in each body memory scope between iterations. Ignored for {@code ACCUMULATE} and
     * {@code FRESH_PER_ITERATION}. Must be {@code >= 1} when {@code memoryMode == WINDOW}.
     * Default: {@code 0} (only used when {@code memoryMode == WINDOW}).
     */
    int memoryWindowSize;

    /**
     * If {@code true} (default), at the start of every iteration after the first,
     * the loop replaces the body's first task via
     * {@link Task#withRevisionFeedback(String, String, int)} so the LLM is told the
     * iteration number and shown the prior iteration's final body output.
     *
     * <p>Set {@code false} to suppress this behaviour -- useful when the body itself
     * routes feedback through {@link net.agentensemble.memory.MemoryScope} or
     * {@code Task.context}.
     */
    boolean injectFeedback;

    /**
     * Outer-DAG dependencies. Same semantics as {@code Task.context}: the loop
     * does not start until all listed tasks have completed. Default: empty list.
     */
    @Singular("context")
    List<Task> context;

    @Override
    public String toString() {
        return "Loop{name='" + name + "', body=" + body.size() + " tasks, "
                + "maxIterations=" + maxIterations + ", outputMode=" + outputMode
                + ", memoryMode=" + memoryMode + "}";
    }

    /**
     * Custom builder that applies defaults and validates.
     *
     * <p>Field defaults for non-{@code @Singular} fields are declared here; {@code body}
     * and {@code context} are managed by Lombok's {@code @Singular} generator and must
     * not be manually re-declared as fields in this class.
     */
    public static class LoopBuilder {

        private String name = null;
        private LoopPredicate until = null;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private MaxIterationsAction onMaxIterations = MaxIterationsAction.RETURN_LAST;
        private LoopOutputMode outputMode = LoopOutputMode.LAST_ITERATION;
        private LoopMemoryMode memoryMode = LoopMemoryMode.ACCUMULATE;
        private int memoryWindowSize = 0;
        private boolean injectFeedback = true;

        public Loop build() {
            List<Task> bodyList = body != null ? List.copyOf(body) : List.of();
            List<Task> contextList = context != null ? List.copyOf(context) : List.of();
            validateName();
            validateBody(bodyList);
            // Validate maxIterations before stop-condition so an explicit invalid
            // value (e.g. 0 or negative) reports the precise error rather than the
            // generic "must declare a stop condition" message.
            validateMaxIterations();
            validateStopCondition();
            validateMemoryWindow();
            validateNoNestedLoops(bodyList);
            validateUniqueBodyNames(bodyList);
            validateBodyContextWithinBody(bodyList);
            return new Loop(
                    name,
                    bodyList,
                    until,
                    maxIterations,
                    onMaxIterations,
                    outputMode,
                    memoryMode,
                    memoryWindowSize,
                    injectFeedback,
                    contextList);
        }

        private void validateName() {
            if (name == null || name.isBlank()) {
                throw new ValidationException("Loop name must be non-blank");
            }
        }

        private void validateBody(List<Task> bodyList) {
            if (bodyList.isEmpty()) {
                throw new ValidationException("Loop '" + name + "' body must contain at least one task");
            }
        }

        private void validateStopCondition() {
            if (until == null && maxIterations <= 0) {
                throw new ValidationException("Loop '" + name + "' must declare either an 'until' predicate "
                        + "or a positive maxIterations (or both)");
            }
        }

        private void validateMaxIterations() {
            if (maxIterations < 1) {
                throw new ValidationException("Loop '" + name + "' maxIterations must be >= 1, got: " + maxIterations);
            }
        }

        private void validateMemoryWindow() {
            if (memoryMode == LoopMemoryMode.WINDOW && memoryWindowSize < 1) {
                throw new ValidationException("Loop '" + name + "' has memoryMode=WINDOW but memoryWindowSize ("
                        + memoryWindowSize + ") must be >= 1. Set via Loop.builder().memoryWindowSize(N).");
            }
            if (memoryMode != LoopMemoryMode.WINDOW && memoryWindowSize > 0) {
                throw new ValidationException("Loop '" + name + "' has memoryWindowSize=" + memoryWindowSize
                        + " but memoryMode=" + memoryMode + ". memoryWindowSize is only meaningful when "
                        + "memoryMode=WINDOW; remove the explicit window size or switch modes.");
            }
        }

        private void validateNoNestedLoops(List<Task> bodyList) {
            // Loop is a WorkflowNode but body is List<Task>, so the type system already
            // prevents nested Loops in the body. Guard against future signature changes
            // (e.g. if body ever becomes List<WorkflowNode>) and reject null entries.
            for (Task t : bodyList) {
                if (t == null) {
                    throw new ValidationException("Loop '" + name + "' body must not contain null tasks");
                }
            }
        }

        private void validateUniqueBodyNames(List<Task> bodyList) {
            Set<String> seen = new HashSet<>();
            for (Task t : bodyList) {
                String key = t.getName() != null ? t.getName() : t.getDescription();
                if (!seen.add(key)) {
                    throw new ValidationException("Loop '" + name + "' body has duplicate task name/description: '"
                            + key + "'. Body tasks must be uniquely identifiable.");
                }
            }
        }

        /**
         * Body tasks may only declare {@code context()} on other tasks within the same body.
         * Loops are sealed: outer-DAG dependencies belong on the Loop itself.
         */
        private void validateBodyContextWithinBody(List<Task> bodyList) {
            Set<Task> bodySet = new HashSet<>(bodyList);
            for (Task t : bodyList) {
                List<Task> ctx = t.getContext();
                if (ctx == null) continue;
                for (Task c : ctx) {
                    if (!bodySet.contains(c)) {
                        throw new ValidationException("Loop '" + name + "' body task '"
                                + (t.getName() != null ? t.getName() : t.getDescription())
                                + "' declares context() on a task outside the loop body. "
                                + "Outer-DAG dependencies must be declared on the Loop itself "
                                + "via Loop.builder().context(...).");
                    }
                }
            }
        }
    }
}
