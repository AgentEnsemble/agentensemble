package net.agentensemble.memory;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * An immutable record of a single memory entry stored in a {@link MemoryStore} scope.
 *
 * <p>Memory entries are produced by the framework after each agent task completes and are
 * stored into each scope declared on the task. They can also be written explicitly via
 * {@link MemoryTool}.
 *
 * <p>The {@code metadata} map carries arbitrary string key-value pairs. The framework
 * populates the following keys automatically:
 * <ul>
 *   <li>{@code "agentRole"} -- the role of the agent that produced the entry</li>
 *   <li>{@code "taskDescription"} -- the description of the task that was executed</li>
 * </ul>
 */
@Builder
@Value
public class MemoryEntry {

    /**
     * The raw text content of the memory entry.
     * Typically the agent's task output.
     */
    String content;

    /**
     * Optional parsed structured output.
     * Populated when the task was configured with an {@code outputType} and
     * structured output parsing succeeded.
     * Default: {@code null}.
     */
    Object structuredContent;

    /**
     * When this entry was stored into the memory scope.
     */
    Instant storedAt;

    /**
     * Arbitrary metadata associated with this entry.
     * Never null; may be empty.
     */
    Map<String, String> metadata;

    /**
     * Convenience method to look up a metadata value by key.
     *
     * @param key the metadata key
     * @return the value, or {@code null} if the key is not present
     */
    public String getMeta(String key) {
        return metadata != null ? metadata.get(key) : null;
    }

    /** Standard metadata key for the agent role that produced this entry. */
    public static final String META_AGENT_ROLE = "agentRole";

    /** Standard metadata key for the task description that produced this entry. */
    public static final String META_TASK_DESCRIPTION = "taskDescription";
}
