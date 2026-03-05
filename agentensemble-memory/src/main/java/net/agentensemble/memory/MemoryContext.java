package net.agentensemble.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <h2>Recording task outputs</h2>
 *
 * After each task completes, the framework creates a {@link MemoryRecord} from the
 * task output and calls {@link #record(MemoryRecord)}. This decouples the memory
 * module from the core {@code TaskOutput} type.
 *
 * <h2>Memory operation tracing</h2>
 *
 * When {@code CaptureMode.STANDARD} or higher is active, a {@link MemoryOperationListener}
 * can be registered via {@link #setOperationListener(MemoryOperationListener)} at the start
 * of each task. The listener receives callbacks for every STM write, LTM store, LTM
 * retrieval, and entity lookup. Clear the listener with
 * {@link #clearOperationListener()} in a {@code finally} block to prevent cross-task
 * leakage.
 *
 * <p>The listener is stored in a {@link ThreadLocal} so that concurrent tasks in
 * {@code Workflow.PARALLEL} each maintain their own listener without interfering with
 * each other.
 *
 * Thread safety: {@link #record(MemoryRecord)} and all query methods are safe to call
 * from multiple threads concurrently. Short-term memory uses a
 * {@link java.util.concurrent.CopyOnWriteArrayList} internally, so concurrent
 * writes from parallel tasks do not race. Long-term memory operations are
 * delegated to the user-supplied {@link LongTermMemory} implementation, which
 * must be thread-safe when used with {@code Workflow.PARALLEL}.
 */
public class MemoryContext {

    private static final Logger log = LoggerFactory.getLogger(MemoryContext.class);

    /** Singleton no-op instance used when memory is disabled. */
    private static final MemoryContext DISABLED = new MemoryContext(null, null);

    private final EnsembleMemory config;
    private final ShortTermMemory shortTermMemory;

    /**
     * Per-task listener for memory operation callbacks. Stored in a ThreadLocal so that
     * concurrent tasks in Workflow.PARALLEL each maintain their own listener independently.
     * Set at the start of each task execution and removed in a finally block.
     */
    private final ThreadLocal<MemoryOperationListener> operationListener = new ThreadLocal<>();

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
     * Register a listener to receive callbacks for memory operations on the current thread.
     *
     * <p>Intended to be called at the start of each task execution by the core
     * {@code AgentExecutor} when {@code CaptureMode.STANDARD} or higher is active.
     * Must be paired with a {@link #clearOperationListener()} call in a {@code finally} block.
     *
     * <p>In {@code Workflow.PARALLEL}, each virtual thread maintains its own listener
     * independently via a {@link ThreadLocal}. Setting the listener on one thread does not
     * affect listeners on other threads.
     *
     * <p>Silently ignored when called on the {@link #disabled()} instance.
     *
     * @param listener the listener to register; if {@code null}, the existing listener
     *                 for the calling thread is cleared
     */
    public void setOperationListener(MemoryOperationListener listener) {
        if (listener != null) {
            this.operationListener.set(listener);
        } else {
            this.operationListener.remove();
        }
    }

    /**
     * Remove the current thread's operation listener.
     *
     * <p>Call this in a {@code finally} block to ensure the listener does not leak
     * into subsequent tasks or reused threads. Removes the ThreadLocal entry to
     * prevent memory leaks on pooled or reused threads.
     */
    public void clearOperationListener() {
        this.operationListener.remove();
    }

    /**
     * Return true if any memory type is active (short-term, long-term, or entity memory).
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
        return config != null
                && config.getEntityMemory() != null
                && !config.getEntityMemory().isEmpty();
    }

    /**
     * Record a completed task output into all enabled memory types.
     *
     * Must be called after each agent task completes. Accepts a {@link MemoryRecord}
     * so that the memory module has no compile dependency on the core {@code TaskOutput}
     * type. This method:
     * <ul>
     *   <li>Adds the output to short-term memory (if enabled)</li>
     *   <li>Stores the output in long-term memory (if configured)</li>
     * </ul>
     *
     * <p>When a {@link MemoryOperationListener} is registered on the calling thread,
     * fires {@link MemoryOperationListener#onStmWrite()} and/or
     * {@link MemoryOperationListener#onLtmStore()} after the respective operations.
     *
     * @param record the completed task record; must not be null
     */
    public void record(MemoryRecord record) {
        if (!isActive() || record == null) {
            return;
        }

        MemoryEntry entry = MemoryEntry.builder()
                .content(record.content())
                .agentRole(record.agentRole())
                .taskDescription(record.taskDescription())
                .timestamp(record.completedAt() != null ? record.completedAt() : Instant.now())
                .build();

        MemoryOperationListener listener = operationListener.get();

        if (hasShortTerm()) {
            shortTermMemory.add(entry);
            log.debug(
                    "Recorded short-term memory | Agent: '{}' | STM size: {}",
                    record.agentRole(),
                    shortTermMemory.size());
            if (listener != null) {
                listener.onStmWrite();
            }
        }

        if (hasLongTerm()) {
            config.getLongTerm().store(entry);
            log.debug("Stored long-term memory | Agent: '{}'", record.agentRole());
            if (listener != null) {
                listener.onLtmStore();
            }
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
     * <p>When a {@link MemoryOperationListener} is registered on the calling thread,
     * fires {@link MemoryOperationListener#onLtmRetrieval(Duration)} with the wall-clock
     * time spent on the retrieval query.
     *
     * @param taskDescription the query text (typically the upcoming task description)
     * @return relevant entries ordered by relevance; empty if LTM is disabled
     */
    public List<MemoryEntry> queryLongTerm(String taskDescription) {
        if (!hasLongTerm()) {
            return List.of();
        }
        Instant start = Instant.now();
        List<MemoryEntry> results = config.getLongTerm().retrieve(taskDescription, config.getLongTermMaxResults());
        MemoryOperationListener listener = operationListener.get();
        if (listener != null) {
            listener.onLtmRetrieval(Duration.between(start, Instant.now()));
        }
        return results;
    }

    /**
     * Return all entity facts from entity memory.
     *
     * <p>When a {@link MemoryOperationListener} is registered on the calling thread,
     * fires {@link MemoryOperationListener#onEntityLookup(Duration)} with the wall-clock
     * time spent on the lookup.
     *
     * @return map of entity name to fact; empty if entity memory is disabled or empty
     */
    public Map<String, String> getEntityFacts() {
        if (!hasEntityMemory()) {
            return Map.of();
        }
        Instant start = Instant.now();
        Map<String, String> facts = config.getEntityMemory().getAll();
        MemoryOperationListener listener = operationListener.get();
        if (listener != null) {
            listener.onEntityLookup(Duration.between(start, Instant.now()));
        }
        return facts;
    }
}
