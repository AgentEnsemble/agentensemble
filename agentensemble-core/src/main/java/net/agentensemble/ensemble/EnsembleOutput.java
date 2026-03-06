package net.agentensemble.ensemble;

import java.time.Duration;
import java.util.List;
import lombok.Value;
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
 * <p>Use {@code getExitReason()} to determine whether the pipeline ran to completion
 * or was stopped early by a human reviewer:
 * <pre>
 * EnsembleOutput output = ensemble.run();
 * if (output.getExitReason() == ExitReason.USER_EXIT_EARLY) {
 *     System.out.println("Pipeline stopped at user request after "
 *             + output.getTaskOutputs().size() + " task(s)");
 * }
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
     * {@code ReviewDecision.ExitEarly} and the pipeline
     * was stopped before all tasks completed.
     *
     * <p>When exit reason is {@link ExitReason#USER_EXIT_EARLY}, {@code taskOutputs}
     * contains only the tasks that completed before the exit signal. The {@code raw}
     * field is the output from the last completed task.
     */
    ExitReason exitReason;

    /**
     * Convenience factory method used by workflow executors to build an {@code EnsembleOutput}
     * with an immutable task output list and automatically computed metrics.
     *
     * <p>Sets {@link ExitReason#COMPLETED} as the exit reason.
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
        return new EnsembleOutput(raw, immutable, totalDuration, totalToolCalls, metrics, null, ExitReason.COMPLETED);
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

        public EnsembleOutput build() {
            List<TaskOutput> immutable = taskOutputs != null ? List.copyOf(taskOutputs) : List.of();
            ExecutionMetrics resolvedMetrics = metrics != null ? metrics : ExecutionMetrics.from(immutable);
            ExitReason resolvedExitReason = exitReason != null ? exitReason : ExitReason.COMPLETED;
            return new EnsembleOutput(
                    raw, immutable, totalDuration, totalToolCalls, resolvedMetrics, trace, resolvedExitReason);
        }
    }
}
