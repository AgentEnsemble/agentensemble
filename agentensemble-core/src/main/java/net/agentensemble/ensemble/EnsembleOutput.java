package net.agentensemble.ensemble;

import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import net.agentensemble.Task;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.trace.ExecutionTrace;

/**
 * The result of a complete (or partial) ensemble execution.
 *
 * <p>Contains the final output (from the last completed task), all individual task outputs
 * in execution order, timing information, a count of total tool calls, and the reason
 * the run terminated.
 *
 * <h2>Checking completion status</h2>
 *
 * <pre>
 * EnsembleOutput output = ensemble.run();
 * if (!output.isComplete()) {
 *     System.out.println("Pipeline stopped early: " + output.getExitReason());
 *     System.out.println("Completed tasks: " + output.completedTasks().size());
 * }
 * </pre>
 *
 * <h2>Accessing per-task outputs</h2>
 *
 * <pre>
 * // By position (execution order)
 * List&lt;TaskOutput&gt; tasks = output.completedTasks();
 *
 * // By specific task reference (identity-based)
 * output.getOutput(researchTask).ifPresent(o -&gt; System.out.println(o.getRaw()));
 *
 * // Last completed task
 * output.lastCompletedOutput().ifPresent(o -&gt; System.out.println(o.getRaw()));
 * </pre>
 *
 * <p>Execution metrics (aggregated token counts, latency, cost, etc.) are available
 * via {@code getMetrics()}. The full execution trace (every LLM call, every tool
 * invocation, all prompts, delegation records) is available via {@code getTrace()}.
 * The trace can be serialized to JSON with {@code output.getTrace().toJson(path)}.
 */
@Value
public class EnsembleOutput {

    /** The raw text output from the final completed task. Convenience accessor. */
    String raw;

    /** All task outputs in execution order. */
    List<TaskOutput> taskOutputs;

    /** Total wall-clock duration of the ensemble execution. */
    Duration totalDuration;

    /** Total number of tool calls made across all tasks. */
    int totalToolCalls;

    /**
     * Aggregated execution metrics for the entire run: token consumption,
     * LLM latency, tool execution time, cost estimate, and more.
     *
     * <p>Returns {@link ExecutionMetrics#EMPTY} when metrics were not collected.
     */
    ExecutionMetrics metrics;

    /**
     * Full execution trace for the entire run, including task traces, agent summaries,
     * and all collected metrics and timing data. Serializes to JSON via
     * {@link ExecutionTrace#toJson()}.
     *
     * <p>{@code null} when trace collection was not available (e.g., in legacy test
     * stubs that build EnsembleOutput directly without going through a workflow executor).
     */
    ExecutionTrace trace;

    /**
     * Why the ensemble run terminated.
     *
     * <p>{@link ExitReason#COMPLETED} for a normal full run.
     * {@link ExitReason#USER_EXIT_EARLY} when a review gate returned
     * {@code ReviewDecision.ExitEarly} and the pipeline was stopped before all tasks
     * completed.
     * {@link ExitReason#TIMEOUT} when a review gate timeout expired with
     * {@code onTimeout(EXIT_EARLY)}.
     * {@link ExitReason#ERROR} when an unrecoverable exception terminated the pipeline.
     *
     * <p>Use {@link #isComplete()} as a convenience test for the {@code COMPLETED} case.
     */
    ExitReason exitReason;

    /**
     * Identity-based index from {@link Task} to its {@link TaskOutput}, populated by
     * workflow executors to support {@link #getOutput(Task)}.
     *
     * <p>Excluded from {@code equals()}, {@code hashCode()}, and {@code toString()}
     * because identity maps are not meaningful for value equality and can produce
     * verbose or confusing output.
     *
     * <p>{@code null} when not provided (e.g., in test stubs built without a workflow executor).
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    Map<Task, TaskOutput> taskOutputIndex;

    // ========================
    // Convenience methods
    // ========================

    /**
     * Returns {@code true} when all tasks in the ensemble completed successfully.
     *
     * <p>Equivalent to {@code getExitReason() == ExitReason.COMPLETED}.
     *
     * @return true if the entire pipeline ran to completion
     */
    public boolean isComplete() {
        return exitReason == ExitReason.COMPLETED;
    }

    /**
     * Returns the list of task outputs for tasks that finished before (or caused) the
     * termination signal.
     *
     * <p>This is always safe to call regardless of exit reason. For a complete run
     * ({@link #isComplete()} == true), this returns all task outputs. For a partial
     * run, it returns only the tasks that completed.
     *
     * <p>This method is an alias for {@code getTaskOutputs()}.
     *
     * @return an immutable list of completed task outputs in execution order
     */
    public List<TaskOutput> completedTasks() {
        return taskOutputs;
    }

    /**
     * Returns the output of the last task that completed, or an empty {@link Optional}
     * when no tasks completed.
     *
     * <p>Useful for accessing the final result without knowing how many tasks ran:
     * <pre>
     * output.lastCompletedOutput()
     *     .map(TaskOutput::getRaw)
     *     .ifPresent(System.out::println);
     * </pre>
     *
     * @return the last completed {@link TaskOutput}, or empty if no tasks completed
     */
    public Optional<TaskOutput> lastCompletedOutput() {
        if (taskOutputs == null || taskOutputs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(taskOutputs.getLast());
    }

    /**
     * Returns the {@link TaskOutput} for the given task, using identity-based lookup.
     *
     * <p>The returned value is present only when the task ran and completed before the
     * ensemble terminated. Tasks that were skipped or never started (e.g., due to
     * exit-early or an error in a dependency) return an empty Optional.
     *
     * <pre>
     * output.getOutput(researchTask).ifPresent(o -&gt;
     *     System.out.println("Research: " + o.getRaw()));
     * </pre>
     *
     * @param task the task to look up; uses object identity, not field equality
     * @return the output for the task, or empty if the task did not complete or the
     *         task-output index was not populated
     */
    public Optional<TaskOutput> getOutput(Task task) {
        if (task == null || taskOutputIndex == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(taskOutputIndex.get(task));
    }

    // ========================
    // Factory methods
    // ========================

    /**
     * Convenience factory method used by workflow executors to build an {@code EnsembleOutput}
     * with an immutable task output list and automatically computed metrics.
     *
     * <p>Sets {@link ExitReason#COMPLETED} as the exit reason.
     * The task output index is not populated; use the {@link Builder} when you need
     * {@link #getOutput(Task)} support.
     *
     * @param raw            the final raw text output
     * @param taskOutputs    all task outputs (copied to an immutable list)
     * @param totalDuration  total run duration
     * @param totalToolCalls total tool calls across all tasks
     * @return the built EnsembleOutput
     */
    public static EnsembleOutput of(
            String raw, List<TaskOutput> taskOutputs, Duration totalDuration, int totalToolCalls) {
        List<TaskOutput> immutable = taskOutputs != null ? List.copyOf(taskOutputs) : List.of();
        ExecutionMetrics metrics = ExecutionMetrics.from(immutable);
        return new EnsembleOutput(
                raw, immutable, totalDuration, totalToolCalls, metrics, null, ExitReason.COMPLETED, null);
    }

    /**
     * Builder-style access. Use {@link #of(String, List, Duration, int)} for the common
     * workflow-executor use case, or construct directly for test scenarios.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@code EnsembleOutput}. */
    public static class Builder {
        private String raw;
        private List<TaskOutput> taskOutputs;
        private Duration totalDuration;
        private int totalToolCalls;
        private ExecutionMetrics metrics;
        private ExecutionTrace trace;
        private ExitReason exitReason;
        private Map<Task, TaskOutput> taskOutputIndex;

        public Builder raw(String raw) {
            this.raw = raw;
            return this;
        }

        public Builder taskOutputs(List<TaskOutput> taskOutputs) {
            this.taskOutputs = taskOutputs;
            return this;
        }

        public Builder totalDuration(Duration totalDuration) {
            this.totalDuration = totalDuration;
            return this;
        }

        public Builder totalToolCalls(int totalToolCalls) {
            this.totalToolCalls = totalToolCalls;
            return this;
        }

        public Builder metrics(ExecutionMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder trace(ExecutionTrace trace) {
            this.trace = trace;
            return this;
        }

        /**
         * Set the reason the run terminated.
         *
         * @param exitReason the exit reason; defaults to {@link ExitReason#COMPLETED} when null
         * @return this builder
         */
        public Builder exitReason(ExitReason exitReason) {
            this.exitReason = exitReason;
            return this;
        }

        /**
         * Set the identity-based task-to-output index for {@link EnsembleOutput#getOutput(Task)}.
         *
         * <p>The provided map is copied to an internal {@link IdentityHashMap} to guarantee
         * identity-based key semantics regardless of the input map type.
         *
         * @param taskOutputIndex map from task to its output; may be null (disables getOutput)
         * @return this builder
         */
        public Builder taskOutputIndex(Map<Task, TaskOutput> taskOutputIndex) {
            this.taskOutputIndex = taskOutputIndex;
            return this;
        }

        public EnsembleOutput build() {
            List<TaskOutput> immutable = taskOutputs != null ? List.copyOf(taskOutputs) : List.of();
            ExecutionMetrics resolvedMetrics = metrics != null ? metrics : ExecutionMetrics.from(immutable);
            ExitReason resolvedExitReason = exitReason != null ? exitReason : ExitReason.COMPLETED;

            Map<Task, TaskOutput> resolvedIndex = null;
            if (taskOutputIndex != null) {
                // SuppressWarnings: intentional intermingling -- we want to copy entries
                // from the caller's map into a fresh IdentityHashMap to guarantee
                // identity-based key semantics regardless of the input map type.
                @SuppressWarnings("IdentityHashMapUsage")
                IdentityHashMap<Task, TaskOutput> copy = new IdentityHashMap<>(taskOutputIndex);
                resolvedIndex = Collections.unmodifiableMap(copy);
            }

            return new EnsembleOutput(
                    raw,
                    immutable,
                    totalDuration,
                    totalToolCalls,
                    resolvedMetrics,
                    trace,
                    resolvedExitReason,
                    resolvedIndex);
        }
    }
}
