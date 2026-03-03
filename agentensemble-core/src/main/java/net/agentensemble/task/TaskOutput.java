package net.agentensemble.task;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * The result produced by an agent executing a single task.
 *
 * TaskOutput instances are immutable and carry the complete result alongside
 * tracing metadata (which agent produced it, when, and how many tool calls
 * were made).
 */
@Builder
@Value
public class TaskOutput {

    /** The complete text output from the agent. */
    @NonNull String raw;

    /** The original task description (for traceability). */
    @NonNull String taskDescription;

    /** The role of the agent that produced this output. */
    @NonNull String agentRole;

    /** When this task completed (UTC). */
    @NonNull Instant completedAt;

    /** How long the task took to execute. */
    @NonNull Duration duration;

    /** Number of tool invocations during execution. */
    int toolCallCount;
}
