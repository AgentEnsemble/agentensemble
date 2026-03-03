package net.agentensemble.memory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory store for task outputs produced during a single ensemble run.
 *
 * Short-term memory is scoped to one call to {@code Ensemble.run()} -- a new
 * instance is created at the start of each run and discarded when the run
 * completes.
 *
 * Thread-safe: uses a {@link CopyOnWriteArrayList} so concurrent task completions
 * (in {@code Workflow.PARALLEL}) can safely write entries while other threads read.
 * Reads return a stable snapshot at the time of the call; they do not block writers.
 *
 * When short-term memory is enabled, all task outputs from the current run
 * are accumulated here and injected into subsequent agents' prompts,
 * regardless of explicit {@code context} declarations on the Task.
 */
public class ShortTermMemory {

    private final List<MemoryEntry> entries = new CopyOnWriteArrayList<>();

    /**
     * Add a memory entry to short-term memory.
     *
     * @param entry the entry to add; must not be null
     */
    public void add(MemoryEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("MemoryEntry must not be null");
        }
        entries.add(entry);
    }

    /**
     * Return a snapshot of all entries in the order they were recorded.
     *
     * Returns an immutable copy so callers receive a stable list that does not
     * reflect subsequent writes from concurrent tasks.
     *
     * @return immutable snapshot of all entries
     */
    public List<MemoryEntry> getEntries() {
        return List.copyOf(entries);
    }

    /**
     * Return true if no entries have been recorded yet.
     *
     * @return true if empty
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Return the number of entries recorded.
     *
     * @return entry count
     */
    public int size() {
        return entries.size();
    }

    /**
     * Remove all entries. Useful for testing.
     */
    public void clear() {
        entries.clear();
    }
}
