package net.agentensemble.network.memory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.agentensemble.memory.EvictionPolicy;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;

/**
 * Cross-ensemble shared memory with configurable consistency.
 *
 * <p>Wraps a {@link MemoryStore} with consistency-aware read/write semantics.
 *
 * <pre>
 * SharedMemory.builder()
 *     .store(MemoryStore.inMemory())
 *     .consistency(Consistency.LOCKED)
 *     .lockProvider(LockProvider.inMemory())
 *     .build();
 * </pre>
 */
public class SharedMemory {

    private final MemoryStore store;
    private final Consistency consistency;
    private final LockProvider lockProvider;
    private final ConcurrentHashMap<String, AtomicLong> versions = new ConcurrentHashMap<>();

    private SharedMemory(MemoryStore store, Consistency consistency, LockProvider lockProvider) {
        this.store = store;
        this.consistency = consistency;
        this.lockProvider = lockProvider;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Store an entry using the configured consistency model. */
    public void store(String scope, MemoryEntry entry) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        switch (consistency) {
            case EVENTUAL, EXTERNAL -> store.store(scope, entry);
            case LOCKED -> {
                try (AutoCloseable lock = lockProvider.lock(scope)) {
                    store.store(scope, entry);
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException("Lock operation failed", e);
                }
            }
            case OPTIMISTIC -> {
                versions.computeIfAbsent(scope, k -> new AtomicLong(0)).incrementAndGet();
                store.store(scope, entry);
            }
        }
    }

    /** Store with optimistic version check. Throws ConcurrentMemoryStoreException on conflict. */
    public void store(String scope, MemoryEntry entry, long expectedVersion) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        if (consistency != Consistency.OPTIMISTIC) {
            store(scope, entry);
            return;
        }
        AtomicLong versionCounter = versions.computeIfAbsent(scope, k -> new AtomicLong(0));
        if (!versionCounter.compareAndSet(expectedVersion, expectedVersion + 1)) {
            throw new ConcurrentMemoryStoreException(scope, expectedVersion);
        }
        store.store(scope, entry);
    }

    /** Retrieve entries. */
    public List<MemoryEntry> retrieve(String scope, String query, int maxResults) {
        Objects.requireNonNull(scope, "scope must not be null");
        switch (consistency) {
            case LOCKED -> {
                try (AutoCloseable lock = lockProvider.lock(scope)) {
                    return store.retrieve(scope, query, maxResults);
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException("Lock operation failed", e);
                }
            }
            default -> {
                return store.retrieve(scope, query, maxResults);
            }
        }
    }

    /** Retrieve with version info for optimistic mode. */
    public VersionedResult retrieveVersioned(String scope, String query, int maxResults) {
        Objects.requireNonNull(scope, "scope must not be null");
        long version = versions.computeIfAbsent(scope, k -> new AtomicLong(0)).get();
        List<MemoryEntry> entries = store.retrieve(scope, query, maxResults);
        return new VersionedResult(entries, version);
    }

    /** Apply eviction policy. */
    public void evict(String scope, EvictionPolicy policy) {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        switch (consistency) {
            case LOCKED -> {
                try (AutoCloseable lock = lockProvider.lock(scope)) {
                    store.evict(scope, policy);
                } catch (Exception e) {
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException("Lock operation failed", e);
                }
            }
            default -> store.evict(scope, policy);
        }
    }

    public MemoryStore store() {
        return store;
    }

    public Consistency consistency() {
        return consistency;
    }

    public LockProvider lockProvider() {
        return lockProvider;
    }

    public static final class Builder {
        private MemoryStore store;
        private Consistency consistency = Consistency.EVENTUAL;
        private LockProvider lockProvider;

        private Builder() {}

        public Builder store(MemoryStore store) {
            this.store = Objects.requireNonNull(store, "store must not be null");
            return this;
        }

        public Builder consistency(Consistency consistency) {
            this.consistency = Objects.requireNonNull(consistency, "consistency must not be null");
            return this;
        }

        public Builder lockProvider(LockProvider lockProvider) {
            this.lockProvider = Objects.requireNonNull(lockProvider, "lockProvider must not be null");
            return this;
        }

        public SharedMemory build() {
            Objects.requireNonNull(store, "store is required");
            if (consistency == Consistency.LOCKED && lockProvider == null) {
                throw new IllegalStateException("lockProvider is required for LOCKED consistency");
            }
            return new SharedMemory(store, consistency, lockProvider);
        }
    }
}
