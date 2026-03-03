package net.agentensemble.memory;

import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runtime memory state for a single ensemble run.
 *
 * Created at the start of each {@code Ensemble.run()} call and passed through
 * the execution pipeline. Coordinates all three memory types:
 * <ul>
 *   <li>Short-term memory: accumulated task outputs from this run</li>
 *   <li>Long-term memory: persistent store queried and updated per task</li>
 *   <li>Entity memory: static key-value facts injected into every prompt</li>
 * </ul>
 *
 * Use {@link #disabled()} for a no-op instance when no memory is configured.
 * Use {@link #from(EnsembleMemory)} to create an active context.
 *
 * This class is not thread-safe. It is designed for sequential ensemble
 * execution where tasks complete one at a time.
 */
public class MemoryContext {

    private static final Logger log = LoggerFactory.getLogger(MemoryContext.class);

    /** Singleton no-op instance used when memory is disabled. */
    private static final MemoryContext DISABLED = new MemoryContext(null, null);

    private final EnsembleMemory config;
    private final ShortTermMemory shortTermMemory;

    private MemoryContext(EnsembleMemory config, ShortTermMemory shortTermMemory) {
        this.config = config;
        this.shortTermMemory = shortTermMemory;
    }

    /**
     * Create a no-op memory context. All query methods return empty results
     * and record() is a no-op.
     *
     * @return the shared disabled instance
     */
    public static MemoryContext disabled() {
        return DISABLED;
    }

    /**
     * Create an active memory context from the given configuration.
     *
     * @param config the ensemble memory configuration; must not be null
     * @return a new MemoryContext ready for use in a single run
     */
    public static MemoryContext from(EnsembleMemory config) {
        if (config == null) {
            throw new IllegalArgumentException("EnsembleMemory config must not be null");
        }
        ShortTermMemory stm = config.isShortTerm() ? new ShortTermMemory() : null;
        return new MemoryContext(config, stm);
    }

    /**
     * Return true if any memory type is active (short-term, long-term, or entity memory).
     *
     * An {@code EnsembleMemory} configuration object present with no actual memory types
     * enabled would return false, correctly preventing "Memory enabled" log noise.
     *
     * @return true only when at least one memory type will inject or record data
     */
    public boolean isActive() {
        return hasShortTerm() || hasLongTerm() || hasEntityMemory();
    }

    /**
     * Return true if short-term memory is enabled.
     *
     * @return true if short-term memory will accumulate task outputs
     */
    public boolean hasShortTerm() {
        return config != null && config.isShortTerm() && shortTermMemory != null;
    }

    /**
     * Return true if long-term memory is configured.
     *
     * @return true if long-term memory will store and retrieve entries
     */
    public boolean hasLongTerm() {
        return config != null && config.getLongTerm() != null;
    }

    /**
     * Return true if entity memory is configured.
     *
     * @return true if entity facts will be injected into prompts
     */
    public boolean hasEntityMemory() {
        return config != null && config.getEntityMemory() != null
                && !config.getEntityMemory().isEmpty();
    }

    /**
     * Record a completed task output into all enabled memory types.
     *
     * Must be called after each agent task completes. This method:
     * <ul>
     *   <li>Adds the output to short-term memory (if enabled)</li>
     *   <li>Stores the output in long-term memory (if configured)</li>
     * </ul>
     *
     * @param output the completed task output; must not be null
     */
    public void record(TaskOutput output) {
        if (!isActive() || output == null) {
            return;
        }

        MemoryEntry entry = MemoryEntry.builder()
                .content(output.getRaw())
                .agentRole(output.getAgentRole())
                .taskDescription(output.getTaskDescription())
                .timestamp(output.getCompletedAt() != null ? output.getCompletedAt() : Instant.now())
                .build();

        if (hasShortTerm()) {
            shortTermMemory.add(entry);
            log.debug("Recorded short-term memory | Agent: '{}' | STM size: {}",
                    output.getAgentRole(), shortTermMemory.size());
        }

        if (hasLongTerm()) {
            config.getLongTerm().store(entry);
            log.debug("Stored long-term memory | Agent: '{}'", output.getAgentRole());
        }
    }

    /**
     * Return all short-term memory entries from this run, in recording order.
     *
     * @return unmodifiable list of short-term entries; empty if STM is disabled
     */
    public List<MemoryEntry> getShortTermEntries() {
        if (!hasShortTerm()) {
            return List.of();
        }
        return shortTermMemory.getEntries();
    }

    /**
     * Query long-term memory for entries relevant to the given task description.
     *
     * @param taskDescription the query text (typically the upcoming task description)
     * @return relevant entries ordered by relevance; empty if LTM is disabled
     */
    public List<MemoryEntry> queryLongTerm(String taskDescription) {
        if (!hasLongTerm()) {
            return List.of();
        }
        return config.getLongTerm().retrieve(taskDescription, config.getLongTermMaxResults());
    }

    /**
     * Return all entity facts from entity memory.
     *
     * @return map of entity name to fact; empty if entity memory is disabled or empty
     */
    public Map<String, String> getEntityFacts() {
        if (!hasEntityMemory()) {
            return Map.of();
        }
        return config.getEntityMemory().getAll();
    }
}
