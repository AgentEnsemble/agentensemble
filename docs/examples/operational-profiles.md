# Operational Profiles

This example demonstrates how to define and apply operational profiles that adjust
ensemble capacity targets and pre-load shared memory ahead of anticipated load changes.

---

## Building a Profile

A `NetworkProfile` declares capacity targets per ensemble and optional pre-load
directives that seed shared memory when the profile is applied.

```java
import net.agentensemble.network.profile.Capacity;
import net.agentensemble.network.profile.NetworkProfile;

NetworkProfile weekendProfile = NetworkProfile.builder()
    .name("sporting-event-weekend")
    .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .ensemble("maintenance", Capacity.replicas(1).maxConcurrent(10))
    .preload("kitchen", "inventory", "Extra beer and ice stocked for game day")
    .build();

NetworkProfile quietWeekday = NetworkProfile.builder()
    .name("quiet-weekday")
    .ensemble("front-desk", Capacity.replicas(1).maxConcurrent(20))
    .ensemble("kitchen", Capacity.replicas(1).maxConcurrent(30))
    .ensemble("maintenance", Capacity.replicas(1).maxConcurrent(10))
    .build();
```

`Capacity.replicas(n).maxConcurrent(m)` is the fluent entry point. Set replicas to 0
for scale-to-zero.

---

## Applying Manually

Use `ProfileApplier` to apply a profile immediately. It executes pre-load directives
and broadcasts the capacity change to the network.

```java
import net.agentensemble.network.memory.SharedMemoryRegistry;
import net.agentensemble.network.profile.ProfileApplier;

SharedMemoryRegistry memoryRegistry = new SharedMemoryRegistry();
memoryRegistry.register("inventory", inventoryMemory);

ProfileApplier applier = new ProfileApplier(
    memoryRegistry,
    message -> webSocket.broadcast(message)   // broadcast function
);

// Apply the weekend profile right now
applier.apply(weekendProfile);
```

When `apply()` runs, it first stores each pre-load directive's content into the
matching shared memory scope, then broadcasts a `ProfileAppliedMessage` with the
capacity targets for each ensemble.

---

## Directive-Based Application

Register a `NetworkProfileDirectiveHandler` with the `DirectiveDispatcher` so that
profiles can be applied via the control plane.

```java
import net.agentensemble.directive.DirectiveDispatcher;
import net.agentensemble.network.profile.NetworkProfileDirectiveHandler;
import java.util.Map;

Map<String, NetworkProfile> profiles = Map.of(
    "sporting-event-weekend", weekendProfile,
    "quiet-weekday", quietWeekday
);

DirectiveDispatcher dispatcher = new DirectiveDispatcher();
dispatcher.registerHandler("APPLY_PROFILE",
    new NetworkProfileDirectiveHandler(applier, profiles));
```

Now a control plane message with action `APPLY_PROFILE` and value
`"sporting-event-weekend"` triggers the profile. The handler looks up the profile
by name and calls `applier.apply()`.

---

## Scheduled Profiles

`ProfileScheduler` applies profiles on a timer. Use this for predictable load patterns
like shift changes or recurring events.

```java
import net.agentensemble.network.profile.ProfileScheduler;
import java.time.Duration;

ProfileScheduler scheduler = new ProfileScheduler(applier);

// Apply the weekend profile every 12 hours (e.g., refresh capacity targets)
scheduler.schedule(weekendProfile, Duration.ofMinutes(0), Duration.ofHours(12));

// Or apply a profile once after a delay
scheduler.scheduleOnce(quietWeekday, Duration.ofHours(48));

// Clean up when done
scheduler.close();
```

`schedule()` applies the profile immediately (zero initial delay above), then repeats
at the given interval. `scheduleOnce()` fires once after the specified delay.

---

## Pre-Loading Shared Memory

Profiles can seed shared memory with context before load arrives. The `preload`
directive targets a named shared memory scope registered in the `SharedMemoryRegistry`.

```java
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.network.memory.Consistency;
import net.agentensemble.network.memory.SharedMemory;
import net.agentensemble.network.NetworkConfig;

// 1. Create shared memory for the kitchen's inventory
SharedMemory inventoryMemory = SharedMemory.builder()
    .store(MemoryStore.inMemory())
    .consistency(Consistency.EVENTUAL)
    .build();

// 2. Register it at the network level
NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .sharedMemory("inventory", inventoryMemory)
    .build();

// 3. Also register in the SharedMemoryRegistry for the applier
SharedMemoryRegistry memoryRegistry = new SharedMemoryRegistry();
memoryRegistry.register("inventory", inventoryMemory);

// 4. Build a profile that pre-loads inventory data
NetworkProfile gameDay = NetworkProfile.builder()
    .name("game-day")
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .preload("kitchen", "inventory", "Extra beer and ice stocked for game day")
    .preload("kitchen", "inventory", "Hot dog buns: 500 units pre-ordered")
    .build();

// 5. Apply -- pre-loads fire first, then capacity broadcast
ProfileApplier applier = new ProfileApplier(
    memoryRegistry,
    message -> webSocket.broadcast(message)
);
applier.apply(gameDay);

// Kitchen agents can now retrieve the pre-loaded context
List<MemoryEntry> preloaded = inventoryMemory.retrieve("inventory", "beer", 5);
System.out.println(preloaded.get(0).getContent());
// -> "Extra beer and ice stocked for game day"
```

Pre-loads run before the capacity broadcast, so new replicas that spin up already
have the context they need.

---

## Related

- [Shared Memory Consistency Example](shared-memory-consistency.md)
- [Federation Example](federation.md)
- [Cross-Ensemble Delegation Example](cross-ensemble-delegation.md)
