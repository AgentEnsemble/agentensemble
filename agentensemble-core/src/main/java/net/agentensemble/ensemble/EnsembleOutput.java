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
     * Phase-keyed task output map, populated when the ensemble was run with phases.
     *
     * <p>Maps each phase name to the list of task outputs produced by that phase.
     * Returns an empty map when the ensemble used a flat task list (no phases).
     *
     * <p>Excluded from {@code equals()}, {@code hashCode()}, and {@code toString()}.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    Map<String, List<TaskOutput>> phaseOutputs;

    /**
     * Returns the phase-keyed task output map.
     *
     * <p>Maps each phase name to its task outputs. Empty when phases were not used.
     *
     * @return phase outputs map; never null
     */
    public Map<String, List<TaskOutput>> getPhaseOutputs() {
        return phaseOutputs != null ? phaseOutputs : Map.of();
    }

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

    /**
     * Loop iteration history, keyed by {@code Loop.getName()}. The value is iteration-ordered
     * (index 0 = iteration 1); each iteration is a map from body-task name to that iteration's
     * {@link TaskOutput}.
     *
     * <p>This is a side channel separate from {@link #taskOutputs} -- the loop's outer-DAG-visible
     * outputs (per its {@code LoopOutputMode}) are recorded in {@code taskOutputs} like any
     * other task. The history exists so trace/viz/auditing consumers can see every iteration.
     *
     * <p>Empty when no loops ran. Excluded from {@code equals()}, {@code hashCode()}, and
     * {@code toString()} for the same reasons as {@link #taskOutputIndex}.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    Map<String, List<Map<String, TaskOutput>>> loopHistory;

    /**
     * Loop termination reasons keyed by {@code Loop.getName()}.
     *
     * <p>Values are {@code "predicate"} (the loop's stop predicate fired) or
     * {@code "maxIterations"} (the loop hit its iteration cap). Empty when no loops ran.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    Map<String, String> loopTerminationReasons;

    /**
     * Names of loops that hit their {@code maxIterations} cap without the predicate firing,
     * AND were configured with {@link net.agentensemble.workflow.loop.MaxIterationsAction#RETURN_WITH_FLAG}.
     * Empty unless that termination action was used.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    java.util.Set<String> loopsTerminatedByMaxIterations;

    /**
     * Per-step record of a {@link net.agentensemble.workflow.graph.Graph} execution, when
     * the ensemble used a Graph. Each entry captures the state visited, step number, and
     * output. Visits to the same state appear as multiple entries.
     *
     * <p>Empty list when no Graph ran. Excluded from {@code equals}/{@code hashCode}/{@code toString}
     * (consistent with {@link #taskOutputIndex} and {@link #loopHistory}).
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    List<net.agentensemble.workflow.graph.GraphStep> graphHistory;

    /**
     * Termination reason for the Graph execution, if a Graph ran. One of {@code "terminal"}
     * (reached {@code Graph.END}) or {@code "maxSteps"} (cap hit). {@code null} when no
     * Graph ran.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    String graphTerminationReason;

    /**
     * Set when a Graph hit {@code maxSteps} without reaching {@code END} AND was configured
     * with {@link net.agentensemble.workflow.graph.MaxStepsAction#RETURN_WITH_FLAG}. {@code null}
     * otherwise.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    Boolean graphTerminatedByMaxSteps;

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
     * Returns the iteration history for the named loop, or an empty list if the loop did not run.
     *
     * <p>The outer list is iteration-ordered (index 0 = iteration 1). Each iteration is a map
     * from body-task name to that iteration's {@link TaskOutput}.
     *
     * @param loopName the loop's name, as set on {@code Loop.builder().name(...)}; must not be null
     * @return immutable iteration history; empty list if the loop did not run
     */
    public List<Map<String, TaskOutput>> getLoopHistory(String loopName) {
        if (loopName == null || loopHistory == null) {
            return List.of();
        }
        List<Map<String, TaskOutput>> history = loopHistory.get(loopName);
        return history != null ? history : List.of();
    }

    /**
     * Returns the termination reason for the named loop, or empty if the loop did not run.
     *
     * <p>Possible values: {@code "predicate"}, {@code "maxIterations"}.
     *
     * @param loopName the loop's name; must not be null
     * @return termination reason, or empty if the loop did not run
     */
    public Optional<String> getLoopTerminationReason(String loopName) {
        if (loopName == null || loopTerminationReasons == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(loopTerminationReasons.get(loopName));
    }

    /**
     * Returns true if the named loop hit its {@code maxIterations} cap without the
     * predicate firing AND was configured with {@code MaxIterationsAction.RETURN_WITH_FLAG}.
     *
     * @param loopName the loop's name; must not be null
     * @return true when the loop terminated by hitting max iterations under RETURN_WITH_FLAG
     */
    public boolean wasLoopTerminatedByMaxIterations(String loopName) {
        return loopName != null
                && loopsTerminatedByMaxIterations != null
                && loopsTerminatedByMaxIterations.contains(loopName);
    }

    /**
     * Returns the full per-step history of a Graph execution, or an empty list if no Graph
     * ran. Entries are in execution order; revisits to the same state appear as multiple
     * entries.
     *
     * @return immutable history list; empty if no graph ran
     */
    public List<net.agentensemble.workflow.graph.GraphStep> getGraphHistory() {
        return graphHistory != null ? graphHistory : List.of();
    }

    /**
     * Returns the termination reason for the Graph execution, if a Graph ran.
     *
     * <p>Possible values: {@code "terminal"} (reached {@code Graph.END}), {@code "maxSteps"}
     * (cap hit). Empty if no Graph ran.
     *
     * @return termination reason; empty if no graph ran
     */
    public Optional<String> getGraphTerminationReason() {
        return Optional.ofNullable(graphTerminationReason);
    }

    /**
     * Returns true if the Graph hit its {@code maxSteps} cap without reaching {@code END}
     * AND was configured with {@code MaxStepsAction.RETURN_WITH_FLAG}.
     *
     * @return true when the graph terminated by hitting max steps under RETURN_WITH_FLAG
     */
    public boolean wasGraphTerminatedByMaxSteps() {
        return Boolean.TRUE.equals(graphTerminatedByMaxSteps);
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
                raw,
                immutable,
                totalDuration,
                totalToolCalls,
                metrics,
                null,
                ExitReason.COMPLETED,
                null,
                null,
                Map.of(),
                Map.of(),
                java.util.Set.of(),
                List.of(),
                null,
                null);
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
        private Map<String, List<TaskOutput>> phaseOutputs;
        private Map<Task, TaskOutput> taskOutputIndex;
        private Map<String, List<Map<String, TaskOutput>>> loopHistory;
        private Map<String, String> loopTerminationReasons;
        private java.util.Set<String> loopsTerminatedByMaxIterations;
        private List<net.agentensemble.workflow.graph.GraphStep> graphHistory;
        private String graphTerminationReason;
        private Boolean graphTerminatedByMaxSteps;

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

        /** Set the phase-keyed output map. */
        public Builder phaseOutputs(Map<String, List<TaskOutput>> phaseOutputs) {
            this.phaseOutputs = phaseOutputs;
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

        /** Set the per-loop iteration history (loop name -> iterations -> task-name -> output). */
        public Builder loopHistory(Map<String, List<Map<String, TaskOutput>>> loopHistory) {
            this.loopHistory = loopHistory;
            return this;
        }

        /** Set per-loop termination reasons (loop name -> "predicate" | "maxIterations"). */
        public Builder loopTerminationReasons(Map<String, String> loopTerminationReasons) {
            this.loopTerminationReasons = loopTerminationReasons;
            return this;
        }

        /** Set the names of loops that hit max-iterations under RETURN_WITH_FLAG. */
        public Builder loopsTerminatedByMaxIterations(java.util.Set<String> loops) {
            this.loopsTerminatedByMaxIterations = loops;
            return this;
        }

        /** Set the per-step graph history (when a Graph ensemble ran). */
        public Builder graphHistory(List<net.agentensemble.workflow.graph.GraphStep> history) {
            this.graphHistory = history;
            return this;
        }

        /** Set the graph termination reason (when a Graph ensemble ran). */
        public Builder graphTerminationReason(String reason) {
            this.graphTerminationReason = reason;
            return this;
        }

        /** Set the RETURN_WITH_FLAG flag for graph termination. */
        public Builder graphTerminatedByMaxSteps(Boolean flag) {
            this.graphTerminatedByMaxSteps = flag;
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
                Map<Task, TaskOutput> copy = new IdentityHashMap<>(taskOutputIndex);
                resolvedIndex = Collections.unmodifiableMap(copy);
            }

            Map<String, List<Map<String, TaskOutput>>> resolvedLoopHistory =
                    loopHistory != null ? Map.copyOf(loopHistory) : Map.of();
            Map<String, String> resolvedLoopTerm =
                    loopTerminationReasons != null ? Map.copyOf(loopTerminationReasons) : Map.of();
            java.util.Set<String> resolvedLoopMaxIters = loopsTerminatedByMaxIterations != null
                    ? java.util.Set.copyOf(loopsTerminatedByMaxIterations)
                    : java.util.Set.of();

            List<net.agentensemble.workflow.graph.GraphStep> resolvedGraphHistory =
                    graphHistory != null ? List.copyOf(graphHistory) : List.of();

            return new EnsembleOutput(
                    raw,
                    immutable,
                    totalDuration,
                    totalToolCalls,
                    resolvedMetrics,
                    trace,
                    resolvedExitReason,
                    phaseOutputs,
                    resolvedIndex,
                    resolvedLoopHistory,
                    resolvedLoopTerm,
                    resolvedLoopMaxIters,
                    resolvedGraphHistory,
                    graphTerminationReason,
                    graphTerminatedByMaxSteps);
        }
    }
}
