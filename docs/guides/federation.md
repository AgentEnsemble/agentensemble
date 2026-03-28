# Federation

AgentEnsemble v4.0 supports federation for cross-namespace and cross-cluster capability
sharing. Federation organizes ensembles into **realms** (typically Kubernetes namespaces)
and enables routing requests to providers in other realms when local capacity is
insufficient.

---

## Quick Start

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
```

---

## Realms

A **realm** is a namespace-level discovery and trust boundary. In Kubernetes deployments,
each realm typically maps to a namespace.

```java
// RealmInfo: identity of a realm
RealmInfo realm = new RealmInfo("hotel-downtown", "hotel-downtown-ns");
```

| Field | Description |
|-------|-------------|
| `name` | Logical realm name (e.g., `"hotel-downtown"`) |
| `namespace` | K8s namespace (e.g., `"hotel-downtown-ns"`) |

Ensembles within the same realm can discover each other freely. Cross-realm discovery
requires the remote ensemble to advertise its capacity as **shareable**.

---

## FederationConfig

`FederationConfig` defines the federation topology:

```java
FederationConfig config = FederationConfig.builder()
    .localRealm("hotel-downtown")       // this ensemble's realm
    .federationName("Hotel Chain")       // logical federation group name
    .realm("hotel-airport", "hotel-airport-ns")
    .realm("hotel-beach", "hotel-beach-ns")
    .build();
```

| Field | Description |
|-------|-------------|
| `localRealm` | The realm of this ensemble instance (required) |
| `federationName` | Logical name of the federation group (required) |
| `realms` | Known realms in the federation |

---

## Capacity Advertisement

Ensembles broadcast their current load and availability using `CapacityUpdateMessage`:

```json
{
  "type": "capacity_update",
  "ensemble": "kitchen",
  "realm": "hotel-downtown",
  "status": "available",
  "currentLoad": 0.35,
  "maxConcurrent": 100,
  "shareable": true
}
```

| Field | Description |
|-------|-------------|
| `ensemble` | The ensemble name |
| `realm` | The realm this ensemble belongs to |
| `status` | `"available"`, `"busy"`, or `"draining"` |
| `currentLoad` | Current load as a fraction from 0.0 to 1.0 |
| `maxConcurrent` | Maximum concurrent tasks |
| `shareable` | Whether spare capacity is available to other realms |

When `shareable` is `true`, the ensemble's spare capacity can be used by ensembles
in other realms within the federation.

---

## Routing Hierarchy

The `FederationRegistry` routes requests using a three-level hierarchy:

| Priority | Scope | Condition |
|----------|-------|-----------|
| 1 (highest) | Local realm | Provider is in the same realm as the requester |
| 2 | Same realm (unregistered) | Provider has no realm info (assumed local) |
| 3 (lowest) | Cross-realm | Provider is in a different realm and has `shareable = true` |

Within each level, the **least-loaded** provider is preferred.

```java
FederationRegistry federationRegistry = new FederationRegistry(capabilityRegistry);

// Find the best provider using the routing hierarchy
Optional<String> provider = federationRegistry.findProvider(
    "prepare-meal", "hotel-downtown");

// Find the least-loaded provider across all realms
Optional<String> leastLoaded = federationRegistry.findLeastLoadedProvider(
    "prepare-meal", "hotel-downtown");
```

---

## CapacityAdvertiser

The `CapacityAdvertiser` periodically broadcasts capacity updates for an ensemble:

```java
CapacityAdvertiser advertiser = new CapacityAdvertiser(
    "kitchen",                          // ensemble name
    "hotel-downtown",                   // realm
    () -> computeCurrentLoad(),         // load supplier (0.0 to 1.0)
    100,                                // max concurrent tasks
    true,                               // shareable to other realms
    message -> broadcast(message));     // broadcaster callback

// Start broadcasting every 10 seconds
advertiser.start(Duration.ofSeconds(10));

// Stop when shutting down
advertiser.close();
```

The advertiser runs on a daemon thread and automatically derives `status` from
the load value: `"busy"` when load >= 1.0, `"available"` otherwise.

---

## FederationRegistry

The `FederationRegistry` combines capability discovery with realm awareness and
load-based routing:

```java
FederationRegistry registry = new FederationRegistry(capabilityRegistry);

// Process incoming capacity updates
registry.updateCapacity(capacityUpdateMessage);

// Query capacity for a specific ensemble
Optional<CapacityStatus> status = registry.getCapacity("kitchen");
// status.currentLoad(), status.maxConcurrent(), status.shareable()

// Query the realm of an ensemble
Optional<String> realm = registry.getRealm("kitchen");
```

### CapacityStatus

```java
// Snapshot of an ensemble's capacity
CapacityStatus status = new CapacityStatus(
    "kitchen",          // ensemble
    "hotel-downtown",   // realm
    "available",        // status
    0.35,               // currentLoad
    100,                // maxConcurrent
    true);              // shareable
```

---

## Network Configuration

Enable federation at the network level via `NetworkConfig.builder()`:

```java
NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .ensemble("maintenance", "ws://maintenance:7329/ws")
    .sharedMemory("inventory", SharedMemory.builder()
        .store(MemoryStore.inMemory())
        .consistency(Consistency.OPTIMISTIC)
        .build())
    .federationConfig(FederationConfig.builder()
        .localRealm("hotel-downtown")
        .federationName("Hotel Chain")
        .realm("hotel-airport", "hotel-airport-ns")
        .realm("hotel-beach", "hotel-beach-ns")
        .build())
    .build();
```

---

## Related

- [Discovery](discovery.md)
- [Federation Example](../examples/federation.md)
