package net.agentensemble.memory;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * An immutable record of a single memory -- a task output captured during
 * execution for later injection into agent prompts.
 *
 * Memory entries are produced by the framework after each agent task completes
 * and are stored in short-term or long-term memory depending on configuration.
 */
@Builder
@Value
public class MemoryEntry {

    /** The text content of the memory (typically the agent's task output). */
    String content;

    /** The role of the agent that produced this memory. */
    String agentRole;

    /** The description of the task that produced this memory. */
    String taskDescription;

    /** When this memory was recorded. */
    Instant timestamp;
}
