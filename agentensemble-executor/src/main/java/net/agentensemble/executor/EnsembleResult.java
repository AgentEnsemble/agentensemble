package net.agentensemble.executor;

import java.util.List;

/**
 * The result of executing an {@link EnsembleRequest} via {@link EnsembleExecutor}.
 *
 * <p>Contains the final output from the last completed task, all per-task output strings
 * in execution order, aggregate timing, total tool call count, and the reason the run ended.
 *
 * @param finalOutput     the final text output from the last completed task
 * @param taskOutputs     per-task output strings in execution order; never null
 * @param totalDurationMs wall-clock duration for the full ensemble run in milliseconds
 * @param totalToolCalls  total tool calls made across all tasks
 * @param exitReason      why the ensemble run terminated (e.g., {@code "COMPLETED"}, {@code "ERROR"})
 */
public record EnsembleResult(
        String finalOutput, List<String> taskOutputs, long totalDurationMs, int totalToolCalls, String exitReason) {

    /**
     * Returns {@code true} when all tasks completed successfully without early exit or error.
     *
     * @return true if the exit reason is {@code "COMPLETED"}
     */
    public boolean isComplete() {
        return "COMPLETED".equals(exitReason);
    }
}
