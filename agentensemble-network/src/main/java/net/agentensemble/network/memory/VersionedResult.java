package net.agentensemble.network.memory;

import java.util.List;
import java.util.Objects;
import net.agentensemble.memory.MemoryEntry;

/**
 * Result of a versioned read from {@link SharedMemory} in {@link Consistency#OPTIMISTIC} mode.
 *
 * @param entries the retrieved entries
 * @param version the current version of the scope at read time
 */
public record VersionedResult(List<MemoryEntry> entries, long version) {

    public VersionedResult {
        Objects.requireNonNull(entries, "entries must not be null");
        entries = List.copyOf(entries);
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
    }
}
