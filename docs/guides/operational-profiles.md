# Operational Profiles

AgentEnsemble v3.0.0 introduces operational profiles for pre-planned capacity adjustments
in anticipation of known load changes. A `NetworkProfile` defines per-ensemble capacity
targets and shared memory pre-load directives that can be applied manually, via the
directive system, or on a schedule.

---

## Quick Start

```java
// Define a profile for a busy weekend
NetworkProfile weekendProfile = NetworkProfile.builder()
    .name("sporting-event-weekend")
    .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .preload("kitchen", "inventory", "Extra beer and ice stocked")
    .build();

// Apply it
ProfileApplier applier = new ProfileApplier(sharedMemoryRegistry, message -> broadcast(message));
applier.apply(weekendProfile);
```

---

## NetworkProfile

A `NetworkProfile` bundles a name, per-ensemble capacity targets, and pre-load
directives into a single deployable unit:

```java
NetworkProfile profile = NetworkProfile.builder()
    .name("sporting-event-weekend")
    .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .ensemble("maintenance", Capacity.replicas(2).maxConcurrent(20))
    .preload("kitchen", "inventory", "Extra beer and ice stocked")
    .preload("front-desk", "alerts", "High occupancy expected this weekend")
    .build();
```

| Method | Description |
|--------|-------------|
| `name(String)` | Profile name (required, must not be blank) |
| `ensemble(String, Capacity)` | Capacity target for a named ensemble |
| `preload(String, String, String)` | Pre-load content into a shared memory scope |

---

## Capacity

The `Capacity` record defines replica count and concurrency limits for an ensemble.
It uses a fluent API:

```java
// 4 replicas, 50 max concurrent tasks
Capacity active = Capacity.replicas(4).maxConcurrent(50);

// Scale to zero (dormant)
Capacity dormant = Capacity.replicas(0).dormant(true).build();

// Default maxConcurrent (10) with explicit build
Capacity minimal = Capacity.replicas(1).build();
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `replicas` | `int` | -- | Target replica count (0 for scale-to-zero) |
| `maxConcurrent` | `int` | 10 | Maximum concurrent tasks per replica |
| `dormant` | `boolean` | `false` | Whether the ensemble should be dormant |

---

## Pre-Loading Shared Memory

Pre-load directives inject content into shared memory scopes when a profile is applied.
This seeds ensembles with context before the anticipated load arrives.

```java
NetworkProfile profile = NetworkProfile.builder()
    .name("conference-week")
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .preload("kitchen", "inventory", "Extra stock for 500 conference attendees")
    .preload("front-desk", "events", "Tech conference check-in Monday 8am-12pm")
    .build();
```

Each `preload(ensembleName, scope, content)` creates a `PreloadDirective` that stores
a `MemoryEntry` into the named shared memory scope during profile application. The
scope must be registered in the `SharedMemoryRegistry`.

---

## ProfileApplier

The `ProfileApplier` orchestrates profile application in two steps:

1. **Execute pre-load directives** -- store content into shared memory scopes
2. **Broadcast a `ProfileAppliedMessage`** -- notify all connected ensembles

```java
SharedMemoryRegistry smRegistry = new SharedMemoryRegistry();
smRegistry.register("inventory", SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build());

ProfileApplier applier = new ProfileApplier(
    smRegistry,
    message -> broadcastToNetwork(message));

applier.apply(weekendProfile);
```

If a pre-load directive references a scope that is not registered, the applier logs
a warning and skips it.

---

## Directive Integration

The `NetworkProfileDirectiveHandler` integrates profiles with the directive system,
enabling profile application via `APPLY_PROFILE` control plane directives:

```java
Map<String, NetworkProfile> profiles = Map.of(
    "weekend", weekendProfile,
    "normal", normalProfile);

NetworkProfileDirectiveHandler handler =
    new NetworkProfileDirectiveHandler(applier, profiles);

directiveDispatcher.registerHandler("APPLY_PROFILE", handler);
```

When the ensemble receives an `APPLY_PROFILE` directive with a profile name as
its value, the handler looks up the profile and applies it:

```java
// This directive triggers weekendProfile
Directive directive = Directive.of("APPLY_PROFILE", "weekend");
```

Unknown profile names are logged as warnings and ignored.

---

## ProfileScheduler

The `ProfileScheduler` applies profiles on a recurring schedule:

```java
ProfileScheduler scheduler = new ProfileScheduler(applier);

// Apply the weekend profile every 7 days, starting in 2 hours
scheduler.schedule(weekendProfile,
    Duration.ofHours(2),    // initial delay
    Duration.ofDays(7));    // interval

// Apply a one-shot profile after a delay
scheduler.scheduleOnce(normalProfile, Duration.ofDays(3));

// Shutdown when done
scheduler.close();
```

| Method | Description |
|--------|-------------|
| `schedule(profile, initialDelay, interval)` | Apply at a fixed interval |
| `scheduleOnce(profile, delay)` | Apply once after a delay |
| `close()` | Cancel all scheduled tasks and shut down |

The scheduler runs on a daemon thread and catches exceptions from individual
applications to prevent one failure from stopping the schedule.

---

## ProfileAppliedMessage

When a profile is applied, a `ProfileAppliedMessage` is broadcast to all connected
ensemble dashboards:

```json
{
  "type": "profile_applied",
  "profileName": "sporting-event-weekend",
  "capacities": {
    "front-desk": { "replicas": 4, "maxConcurrent": 50, "dormant": false },
    "kitchen": { "replicas": 3, "maxConcurrent": 100, "dormant": false }
  },
  "appliedAt": "2026-03-28T14:30:00Z"
}
```

Consumers of this message can use the capacity targets to trigger actual scaling
(e.g., adjusting Kubernetes HPA targets or replica counts).

---

## Related

- [Shared Memory](shared-memory.md)
- [Directives](directives.md)
- [Operational Profiles Example](../examples/operational-profiles.md)
