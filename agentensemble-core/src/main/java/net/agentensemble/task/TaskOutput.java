package net.agentensemble.task;

import lombok.Builder;
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
    String raw;

    /** The original task description (for traceability). */
    String taskDescription;

    /** The role of the agent that produced this output. */
    String agentRole;

    /** When this task completed (UTC). */
    Instant completedAt;

    /** How long the task took to execute. */
    Duration duration;

    /** Number of tool invocations during execution. */
    int toolCallCount;
}
