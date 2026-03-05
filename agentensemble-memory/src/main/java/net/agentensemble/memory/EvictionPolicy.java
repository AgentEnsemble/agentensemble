package net.agentensemble.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Strategy for removing entries from a memory scope when the scope grows too large or
 * entries become stale.
 *
 * <p>Eviction policies are applied on-demand via
 * {@link MemoryStore#evict(String, EvictionPolicy)}.
 *
 * <p>Use the built-in factories for the most common strategies:
 * <ul>
 *   <li>{@link #keepLastEntries(int)} -- retain only the {@code n} most recent entries</li>
 *   <li>{@link #keepEntriesWithin(Duration)} -- retain only entries stored within the given
 *       window</li>
 * </ul>
 */
public interface EvictionPolicy {

    /**
     * Apply this policy to the given list of entries and return the entries to retain.
     *
     * <p>The input list is in insertion order (oldest first). Implementations must return
     * an unmodifiable list. The original list must not be mutated.
     *
     * @param entries the current entries in the scope; never null
     * @return the entries to keep after eviction; never null
     */
    List<MemoryEntry> apply(List<MemoryEntry> entries);

    /**
     * Retain only the {@code n} most recently added entries.
     *
     * <p>When the scope contains fewer than {@code n} entries, no entries are evicted.
     *
     * @param n the maximum number of entries to retain; must be greater than zero
     * @return an eviction policy that keeps the last {@code n} entries
     * @throws IllegalArgumentException if {@code n} is not positive
     */
    static EvictionPolicy keepLastEntries(int n) {
        if (n <= 0) {
            throw new IllegalArgumentException("keepLastEntries n must be > 0, got: " + n);
        }
        return entries -> {
            if (entries.size() <= n) {
                return List.copyOf(entries);
            }
            return List.copyOf(entries.subList(entries.size() - n, entries.size()));
        };
    }

    /**
     * Retain only entries whose {@code storedAt} timestamp is within the given
     * duration from the current time.
     *
     * <p>Entries with a {@code null} {@code storedAt} timestamp are always evicted.
     *
     * @param duration the retention window; must be positive (non-zero, non-negative)
     * @return an eviction policy that keeps entries within the given window
     * @throws IllegalArgumentException if {@code duration} is null, zero, or negative
     */
    static EvictionPolicy keepEntriesWithin(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("keepEntriesWithin duration must not be null");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("keepEntriesWithin duration must be positive, got: " + duration);
        }
        return entries -> {
            Instant cutoff = Instant.now().minus(duration);
            return entries.stream()
                    .filter(e -> e.getStoredAt() != null && e.getStoredAt().isAfter(cutoff))
                    .collect(Collectors.toUnmodifiableList());
        };
    }
}
