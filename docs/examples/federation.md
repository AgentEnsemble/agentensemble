# Federation

This example demonstrates cross-realm capability sharing: three hotel properties in a
federation discover each other's tools and route overflow work to the least-loaded
provider.

---

## Federation Configuration

Define the realms in a federation. Each realm is a namespace-level trust boundary --
in Kubernetes, each typically maps to a namespace.

```java
import net.agentensemble.network.NetworkConfig;
import net.agentensemble.network.federation.FederationConfig;

FederationConfig federation = FederationConfig.builder()
    .localRealm("hotel-downtown")
    .federationName("Hotel Chain")
    .realm("hotel-airport", "hotel-airport-ns")
    .realm("hotel-beach", "hotel-beach-ns")
    .build();

NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .ensemble("front-desk", "ws://front-desk:7330/ws")
    .federationConfig(federation)
    .build();
```

The local realm is `hotel-downtown`. The two remote realms (`hotel-airport`,
`hotel-beach`) are known peers in the same federation group.

---

## Capacity Advertisement

Each ensemble periodically broadcasts its load and availability. The
`CapacityAdvertiser` handles the heartbeat.

```java
import net.agentensemble.network.federation.CapacityAdvertiser;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

AtomicInteger activeOrders = new AtomicInteger(0);

CapacityAdvertiser advertiser = new CapacityAdvertiser(
    "kitchen",                              // ensemble name
    "hotel-downtown",                       // realm
    () -> activeOrders.get() / 20.0,        // load supplier (0.0 - 1.0)
    20,                                     // max concurrent tasks
    true,                                   // shareable to other realms
    message -> webSocket.broadcast(message)  // broadcast function
);

advertiser.start(Duration.ofSeconds(10));
```

When `shareable` is `true`, other realms can route overflow work to this ensemble.
Set it to `false` for ensembles that should only serve their local realm.

---

## Cross-Realm Discovery

The `FederationRegistry` routes requests using a three-tier hierarchy:
local ensemble, same realm, then cross-realm (shareable only).

```java
import net.agentensemble.network.CapabilityRegistry;
import net.agentensemble.network.federation.FederationRegistry;
import net.agentensemble.web.protocol.CapacityUpdateMessage;

CapabilityRegistry capabilities = new CapabilityRegistry();
FederationRegistry federationRegistry = new FederationRegistry(capabilities);

// Simulate capacity updates from three kitchens
federationRegistry.updateCapacity(new CapacityUpdateMessage(
    "kitchen-downtown", "hotel-downtown", "busy", 0.95, 20, true));

federationRegistry.updateCapacity(new CapacityUpdateMessage(
    "kitchen-airport", "hotel-airport", "available", 0.3, 20, true));

federationRegistry.updateCapacity(new CapacityUpdateMessage(
    "kitchen-beach", "hotel-beach", "available", 0.5, 15, false));

// Downtown kitchen is at 95% load -- find best provider for "prepare-meal"
Optional<String> provider = federationRegistry.findProvider(
    "prepare-meal", "hotel-downtown");

// Result: "kitchen-airport" (cross-realm, shareable, lowest load)
// kitchen-beach is excluded because shareable=false
System.out.println("Routed to: " + provider.orElse("none"));
```

The routing hierarchy ensures local work stays local when capacity allows.
Cross-realm routing only activates when local providers are overloaded and
the remote ensemble has opted in with `shareable=true`.

---

## Routing Hierarchy

The `findProvider` method applies these rules in order:

1. **Local realm** -- prefer providers in the same realm, pick the one with lowest load
2. **Unknown realm** -- providers without realm information are treated as local
3. **Cross-realm** -- only providers with `shareable=true`, pick lowest load

```java
// Least-loaded across all realms (ignores hierarchy, still respects shareability)
Optional<String> global = federationRegistry.findLeastLoadedProvider(
    "prepare-meal", "hotel-downtown");

// Per-ensemble capacity inspection
federationRegistry.getCapacity("kitchen-airport").ifPresent(status -> {
    System.out.println("Airport kitchen: " + status.status()
        + ", load=" + status.currentLoad()
        + ", shareable=" + status.shareable());
});
// -> "Airport kitchen: available, load=0.3, shareable=true"
```

Use `findProvider` for the standard hierarchy. Use `findLeastLoadedProvider` when
you want the absolute lowest load regardless of realm preference.

---

## Full Wiring Example

Putting federation, capacity advertisement, and network config together:

```java
FederationConfig federation = FederationConfig.builder()
    .localRealm("hotel-downtown")
    .federationName("Hotel Chain")
    .realm("hotel-airport", "hotel-airport-ns")
    .realm("hotel-beach", "hotel-beach-ns")
    .build();

NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .federationConfig(federation)
    .build();

NetworkClientRegistry registry = new NetworkClientRegistry(config);

// Start advertising capacity
CapacityAdvertiser advertiser = new CapacityAdvertiser(
    "kitchen", "hotel-downtown",
    () -> activeOrders.get() / 20.0, 20, true,
    message -> registry.broadcast(message));
advertiser.start(Duration.ofSeconds(10));

// Use federation-aware tool resolution in a task
EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Prepare wagyu steak, medium-rare, for room 403")
        .tools(NetworkToolCatalog.tagged("food", registry))
        .build())
    .build()
    .run();

// Clean up
advertiser.close();
registry.close();
```

---

## Related

- [Discovery & Catalog Example](discovery-catalog.md)
- [Cross-Ensemble Delegation Example](cross-ensemble-delegation.md)
- [Operational Profiles Example](operational-profiles.md)
