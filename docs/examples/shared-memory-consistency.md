# Shared Memory Consistency

This example demonstrates how multiple ensembles share state through `SharedMemory` with
four consistency modes: EVENTUAL, LOCKED, OPTIMISTIC, and EXTERNAL.

---

## EVENTUAL Mode (Guest Preferences)

Last-write-wins with no coordination. Ideal for context, preferences, and notes where
conflicts are tolerable.

```java
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.network.memory.Consistency;
import net.agentensemble.network.memory.SharedMemory;

SharedMemory preferences = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build();

// Front desk stores a guest preference
preferences.store("room-403", MemoryEntry.builder()
    .content("Guest prefers extra pillows and late checkout")
    .build());

// Room service reads it later -- no coordination overhead
List<MemoryEntry> entries = preferences.retrieve("room-403", "pillows", 5);
System.out.println(entries.get(0).getContent());
// -> "Guest prefers extra pillows and late checkout"
```

Multiple ensembles can write to the same scope concurrently. The last write wins
silently -- no errors, no version tracking.

---

## LOCKED Mode (Room Assignments)

Acquires an exclusive lock before every read and write. Use this when two ensembles
must not assign the same resource simultaneously.

```java
import net.agentensemble.network.memory.LockProvider;

SharedMemory rooms = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.LOCKED)
    .lockProvider(LockProvider.inMemory())
    .build();

// Front desk assigns room 201
rooms.store("room-201", MemoryEntry.builder()
    .content("Assigned to reservation R-8842")
    .build());

// Concierge checks room 201 -- blocked until the write lock releases
List<MemoryEntry> assignment = rooms.retrieve("room-201", "assigned", 1);
System.out.println(assignment.get(0).getContent());
// -> "Assigned to reservation R-8842"
```

If the front desk and concierge both try to assign room 201 at the same instant,
one blocks until the other's lock is released. No data races.

---

## OPTIMISTIC Mode (Inventory Counter)

Uses compare-and-swap semantics. Read a versioned snapshot, then write only if the
version has not changed. On conflict, catch the exception and retry.

```java
import net.agentensemble.network.memory.ConcurrentMemoryStoreException;
import net.agentensemble.network.memory.VersionedResult;

SharedMemory inventory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.OPTIMISTIC)
    .build();

// Seed the inventory
inventory.store("minibar", MemoryEntry.builder()
    .content("beer:12, water:8, wine:4")
    .build());

// CAS retry loop: decrement beer count
boolean updated = false;
while (!updated) {
    VersionedResult snapshot = inventory.retrieveVersioned("minibar", "beer", 1);
    long version = snapshot.version();
    String current = snapshot.entries().get(0).getContent();

    // Parse, decrement, rebuild
    String newContent = current.replace("beer:12", "beer:11");

    try {
        inventory.store("minibar", MemoryEntry.builder()
            .content(newContent)
            .build(), version);
        updated = true;
    } catch (ConcurrentMemoryStoreException e) {
        // Another ensemble modified the scope -- retry with fresh version
        System.out.println("Version conflict on scope '" + e.scope()
            + "' at version " + e.expectedVersion() + ", retrying...");
    }
}
```

OPTIMISTIC mode avoids the overhead of distributed locks while still preventing
lost updates. The retry loop is the caller's responsibility.

---

## EXTERNAL Mode (Database-Backed Store)

The framework delegates all consistency to the underlying store. Use this when
your `MemoryStore` is backed by a database with its own transaction semantics.

```java
SharedMemory external = SharedMemory.builder()
    .store(myDatabaseBackedStore)     // your MemoryStore implementation
    .consistency(Consistency.EXTERNAL)
    .build();

// Reads and writes pass straight through to the backing store.
// The framework adds no locking, versioning, or coordination.
external.store("reservations", MemoryEntry.builder()
    .content("R-8842: confirmed, 2 nights, king bed")
    .build());

List<MemoryEntry> results = external.retrieve("reservations", "R-8842", 10);
```

EXTERNAL mode is a pass-through. If your database enforces serializable isolation
or optimistic locking internally, no additional framework overhead is added.

---

## Network-Level Registration

Register shared memory instances in `NetworkConfig` so they are available to all
ensembles in the network and to the `ProfileApplier` for pre-loading.

```java
import net.agentensemble.network.NetworkConfig;

SharedMemory guestPrefs = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build();

SharedMemory roomAssignments = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.LOCKED)
    .lockProvider(LockProvider.inMemory())
    .build();

NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .ensemble("front-desk", "ws://front-desk:7330/ws")
    .sharedMemory("guest-preferences", guestPrefs)
    .sharedMemory("room-assignments", roomAssignments)
    .build();
```

Any ensemble connected to this network can look up shared memory by name through
the `SharedMemoryRegistry`.

---

## Choosing a Consistency Mode

| Mode | Use case | Trade-off |
|------|----------|-----------|
| EVENTUAL | Preferences, context, notes | Fastest; last-write-wins silently |
| LOCKED | Room assignments, exclusive access | Safe; blocks on contention |
| OPTIMISTIC | Counters, inventory, balances | No lock overhead; caller retries on conflict |
| EXTERNAL | Database-backed stores | Zero framework overhead; your store owns consistency |

---

## Related

- [Cross-Ensemble Delegation Example](cross-ensemble-delegation.md)
- [Operational Profiles Example](operational-profiles.md)
