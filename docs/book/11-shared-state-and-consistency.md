# Chapter 11: Shared State and Consistency

## Two Kinds of Truth

A hotel maintains two fundamentally different kinds of information.

The first kind is **advisory context**: "Guest Smith prefers a quiet room on upper floors."
"The boiler in building 2 has been unreliable this month." "The conference group tends to
order room service late." This information is useful, imprecise, and tolerant of
inconsistency. If two departments have slightly different notes about Guest Smith's
preferences, no harm is done. The agents reading the notes can synthesize both perspectives.

The second kind is **authoritative state**: "Room 403 is assigned to Guest Smith." "We have
3 replacement valves in inventory." "PO #4821 has been submitted." This information must
be accurate. If two departments disagree about who is in room 403, someone shows up to a
room that is already occupied. If the inventory count is wrong, procurement orders parts
that already exist -- or fails to order parts that are needed.

The Ensemble Network recognizes this distinction and provides different consistency models
for each.

## Advisory Memory: Eventual Consistency

Shared memory scopes used for advisory context operate with eventual consistency and
last-write-wins semantics. This is the default and covers the majority of cross-ensemble
shared state.

```java
network.sharedMemory("guest-preferences", SharedMemory.builder()
    .store(MemoryStore.embeddings(embeddingModel, store))
    .consistency(Consistency.EVENTUAL)
    .build());
```

When the front desk writes "Guest Smith likes extra pillows" and room service writes
"Guest Smith always orders decaf," both entries are stored. Neither overwrites the other.
When a task reads from the `guest-preferences` scope, both entries are retrieved (via
semantic similarity or recency-based retrieval) and injected into the agent's prompt.

Concurrent writes do not conflict because they are additive. Memory entries are appended,
not replaced. The LLM reads all available context and synthesizes it. This is the natural
language equivalent of eventual consistency: the agent gets a slightly stale or slightly
redundant view, and it handles it gracefully because it is interpreting natural language,
not parsing a strict schema.

## Authoritative State: Choose Your Consistency

For state that must be accurate, the framework provides configurable consistency models
per scope.

### Distributed Locking

For exclusive access -- only one ensemble can modify this state at a time:

```java
network.sharedMemory("room-assignments", SharedMemory.builder()
    .store(MemoryStore.redis(client))
    .consistency(Consistency.LOCKED)
    .lockProvider(LockProvider.redis(client))
    .build());
```

When the front desk assigns a guest to a room, it acquires a distributed lock on the
`room-assignments` scope, reads the current state, writes the new assignment, and releases
the lock. While the lock is held, no other ensemble can modify room assignments.

The lock is implemented via the `LockProvider` SPI. The Redis implementation uses
`SETNX` with an expiry (or the Redlock algorithm for multi-node Redis). Other
implementations (ZooKeeper, database advisory locks) are pluggable.

Lock timeouts prevent deadlocks. If the ensemble holding the lock crashes without releasing
it, the lock expires after the configured timeout and another ensemble can acquire it.

### Optimistic Concurrency

For counters and quantities where locking is too heavy:

```java
network.sharedMemory("inventory-count", SharedMemory.builder()
    .store(MemoryStore.redis(client))
    .consistency(Consistency.OPTIMISTIC)
    .build());
```

Optimistic concurrency uses compare-and-swap. The ensemble reads the current value (and
its version), computes the new value, and writes with the version check. If another
ensemble modified the value between the read and the write, the write fails and the
ensemble retries with the updated value.

This is lighter than locking for high-contention scenarios. Multiple ensembles can read
the inventory count concurrently. Only the write path requires conflict detection.

### External Systems

For state managed by a database, booking system, or ERP:

```java
network.sharedMemory("financial-ledger", SharedMemory.builder()
    .consistency(Consistency.EXTERNAL)
    .build());
```

The `EXTERNAL` consistency model means the framework does not manage the state at all.
The ensemble's agents access the state through tools (database query tools, API tools)
that connect directly to the external system. The external system provides its own
consistency guarantees (ACID transactions, optimistic locking, etc.).

This is the right choice for systems of record. The hotel's financial ledger is not a
memory scope -- it is a database. Agents query it and update it through tools that respect
the database's transactional semantics.

## The Right Model for the Right Data

| Data | Consistency | Why |
|---|---|---|
| Guest preferences | EVENTUAL | Advisory; LLM tolerates inconsistency |
| Interaction notes | EVENTUAL | Additive context; no conflicts |
| Room assignments | LOCKED | Exclusive; double-booking is unacceptable |
| Inventory counts | OPTIMISTIC | Concurrent reads, occasional writes |
| Financial records | EXTERNAL | System of record with ACID guarantees |
| Purchase orders | EXTERNAL | External procurement system |

The key insight: **most shared state in an AI agent network is advisory context**, not
authoritative data. The LLM is the consistency model for advisory context -- it reads
whatever is available and makes sense of it. Only the genuinely authoritative data needs
stronger consistency, and that data usually lives in an external system that already
provides it.

The framework does not try to build a distributed database. It provides a spectrum of
consistency options and lets the user choose per scope. For most scopes, eventual
consistency is not just acceptable -- it is preferable, because it is simpler, faster,
and more resilient.
