# Shared Memory

AgentEnsemble v3.0.0 introduces cross-ensemble shared memory with configurable consistency
models. `SharedMemory` wraps a standard `MemoryStore` with consistency-aware read/write
semantics, enabling multiple ensembles to safely share state across a network.

---

## Quick Start

```java
// Create shared memory with eventual consistency (default)
SharedMemory sharedMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build();

// Store an entry
sharedMemory.store("inventory", MemoryEntry.builder()
    .content("Wagyu beef: 12 portions remaining")
    .storedAt(Instant.now())
    .build());

// Retrieve entries
List<MemoryEntry> entries = sharedMemory.retrieve("inventory", "beef", 10);
```

---

## Consistency Models

Each consistency model defines coordination behavior for concurrent reads and writes
to a shared memory scope across ensembles.

| Model | Behavior | Use Case |
|-------|----------|----------|
| `EVENTUAL` | Last-write-wins, no coordination | Context, preferences, notes |
| `LOCKED` | Distributed lock before each read/write | Room assignments, exclusive access |
| `OPTIMISTIC` | Compare-and-swap with version check; retry on conflict | Counters, inventory |
| `EXTERNAL` | Framework does not manage consistency; your tools handle it | Custom coordination |

### Eventual (default)

```java
SharedMemory sharedMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build();
```

No coordination -- all writes go directly to the backing store. Suitable when
conflicts are unlikely or acceptable.

### Locked

```java
SharedMemory sharedMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.LOCKED)
    .lockProvider(LockProvider.inMemory())
    .build();
```

Every `store()` and `retrieve()` acquires an exclusive lock on the scope first.
A `LockProvider` is required when using `LOCKED` consistency.

### Optimistic

```java
SharedMemory sharedMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.OPTIMISTIC)
    .build();
```

Writes increment a per-scope version counter. Use `retrieveVersioned()` and the
version-aware `store()` overload for conflict detection.

### External

```java
SharedMemory sharedMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EXTERNAL)
    .build();
```

The framework performs no coordination. Your application or tools are responsible
for managing consistency.

---

## LockProvider SPI

The `LockProvider` interface provides distributed locking for `LOCKED` consistency mode.

```java
public interface LockProvider {
    AutoCloseable lock(String scope);
    boolean tryLock(String scope, Duration timeout);
    void unlock(String scope);
}
```

### In-memory (development and testing)

```java
LockProvider lockProvider = LockProvider.inMemory();
```

Backed by `ReentrantLock`. Suitable for single-JVM development and testing.

### Production

For multi-process deployments, implement the `LockProvider` interface with your
distributed lock infrastructure (e.g., Redis, ZooKeeper).

---

## Optimistic Concurrency

Optimistic mode uses version numbers to detect conflicting writes without locking.

### Read-modify-write pattern

```java
SharedMemory sharedMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.OPTIMISTIC)
    .build();

// 1. Read with version
VersionedResult result = sharedMemory.retrieveVersioned("inventory", "beef", 10);
long version = result.version();
List<MemoryEntry> entries = result.entries();

// 2. Modify and write with expected version
try {
    sharedMemory.store("inventory", MemoryEntry.builder()
        .content("Wagyu beef: 11 portions remaining")
        .storedAt(Instant.now())
        .build(), version);
} catch (ConcurrentMemoryStoreException e) {
    // Another writer updated the scope -- retry from step 1
}
```

### Retry pattern

```java
boolean stored = false;
while (!stored) {
    VersionedResult result = sharedMemory.retrieveVersioned("inventory", "beef", 10);
    MemoryEntry updated = MemoryEntry.builder()
        .content("Updated inventory count")
        .storedAt(Instant.now())
        .build();
    try {
        sharedMemory.store("inventory", updated, result.version());
        stored = true;
    } catch (ConcurrentMemoryStoreException e) {
        // Conflict -- loop will re-read and retry
    }
}
```

---

## SharedMemoryRegistry

The `SharedMemoryRegistry` holds named `SharedMemory` instances at the network level.
Other components (e.g., `ProfileApplier`) look up shared memory by name.

```java
SharedMemoryRegistry registry = new SharedMemoryRegistry();

registry.register("inventory", SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.OPTIMISTIC)
    .build());

registry.register("guest-preferences", SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build());

// Lookup
SharedMemory inventory = registry.get("inventory");
boolean exists = registry.contains("inventory");
Set<String> names = registry.names();
```

---

## Network-Level Registration

Register shared memory instances via `NetworkConfig.builder()` so they are available
across the entire ensemble network:

```java
NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .ensemble("front-desk", "ws://front-desk:7329/ws")
    .sharedMemory("inventory", SharedMemory.builder()
        .store(MemoryStore.inMemory())
        .consistency(Consistency.OPTIMISTIC)
        .build())
    .sharedMemory("guest-preferences", SharedMemory.builder()
        .store(MemoryStore.inMemory())
        .consistency(Consistency.EVENTUAL)
        .build())
    .build();
```

---

## Eviction

Shared memory supports the same eviction policies as the underlying `MemoryStore`:

```java
sharedMemory.evict("inventory", EvictionPolicy.keepLastEntries(10));
```

In `LOCKED` mode, eviction acquires the scope lock before proceeding. In other modes,
eviction delegates directly to the backing store.

---

## Related

- [Memory](memory.md)
- [Shared Memory Consistency Example](../examples/shared-memory-consistency.md)
