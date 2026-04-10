package net.agentensemble.executor;

/**
 * The result of executing a single {@link TaskRequest} via {@link TaskExecutor}.
 *
 * @param output        the final text output produced by the agent
 * @param durationMs    wall-clock duration of the task execution in milliseconds
 * @param toolCallCount total number of tool calls made during execution
 * @param exitReason    why the ensemble run terminated (e.g., {@code "COMPLETED"}, {@code "ERROR"})
 */
public record TaskResult(String output, long durationMs, int toolCallCount, String exitReason) {

    /**
     * Returns {@code true} when the task completed successfully without early exit or error.
     *
     * @return true if the exit reason is {@code "COMPLETED"}
     */
    public boolean isComplete() {
        return "COMPLETED".equals(exitReason);
    }
}
