package net.agentensemble.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory {@link MemoryStore} implementation backed by a {@code ConcurrentHashMap}.
 *
 * <p>Entries are stored in insertion order per scope. Retrieval returns the most recent
 * entries (up to {@code maxResults}) without semantic similarity scoring. This store is
 * suitable for development, testing, and single-JVM use cases.
 *
 * <p>Thread-safe: all operations are safe for concurrent use across virtual threads in
 * {@code Workflow.PARALLEL}.
 *
 * <p>Entries do not survive JVM restarts. To simulate cross-run persistence in tests,
 * keep the same {@code InMemoryStore} instance across multiple {@code Ensemble.run()}
 * invocations.
 */
class InMemoryStore implements MemoryStore {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MemoryEntry>> scopes = new ConcurrentHashMap<>();

    @Override
    public void store(String scope, MemoryEntry entry) {
        validateScope(scope);
        if (entry == null) {
            throw new IllegalArgumentException("entry must not be null");
        }
        scopes.computeIfAbsent(scope, k -> new CopyOnWriteArrayList<>()).add(entry);
    }

    @Override
    public List<MemoryEntry> retrieve(String scope, String query, int maxResults) {
        validateScope(scope);
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be > 0, got: " + maxResults);
        }
        CopyOnWriteArrayList<MemoryEntry> entries = scopes.get(scope);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        // Return the most recent entries (up to maxResults)
        List<MemoryEntry> snapshot = new ArrayList<>(entries);
        int from = Math.max(0, snapshot.size() - maxResults);
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    @Override
    public void evict(String scope, EvictionPolicy policy) {
        validateScope(scope);
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        scopes.computeIfPresent(scope, (k, existing) -> {
            List<MemoryEntry> retained = policy.apply(new ArrayList<>(existing));
            CopyOnWriteArrayList<MemoryEntry> updated = new CopyOnWriteArrayList<>();
            updated.addAll(retained);
            return updated;
        });
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope must not be null or blank");
        }
    }
}
