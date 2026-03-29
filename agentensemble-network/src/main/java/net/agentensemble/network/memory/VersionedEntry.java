package net.agentensemble.network.memory;

import java.util.Objects;
import net.agentensemble.memory.MemoryEntry;

/**
 * A memory entry paired with its version number for optimistic concurrency control.
 *
 * @param entry   the memory entry
 * @param version the version at which this entry was stored
 */
public record VersionedEntry(MemoryEntry entry, long version) {

    public VersionedEntry {
        Objects.requireNonNull(entry, "entry must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
    }
}
