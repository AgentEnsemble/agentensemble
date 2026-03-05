package net.agentensemble.memory;

import java.time.Instant;

/**
 * An immutable data carrier used to record a completed task output into memory.
 *
 * {@code MemoryRecord} decouples the memory subsystem from the core execution types.
 * The framework constructs a {@code MemoryRecord} from a completed task output and
 * passes it to {@link MemoryContext#record(MemoryRecord)} so that the memory module
 * has no compile dependency on {@code agentensemble-core}.
 *
 * @param content         the raw text output produced by the agent
 * @param agentRole       the role of the agent that produced the output
 * @param taskDescription the description of the task that was executed
 * @param completedAt     the instant the task completed; may be null, in which case
 *                        {@link MemoryContext} substitutes {@code Instant.now()}
 */
public record MemoryRecord(String content, String agentRole, String taskDescription, Instant completedAt) {}
