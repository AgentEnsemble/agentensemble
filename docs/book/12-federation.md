# Chapter 12: Federation -- The Hotel Chain

## Beyond a Single Cluster

A single hotel is a network of departments. A hotel chain is a network of hotels.

When Hotel A downtown is hosting a 500-person conference and every department is at
capacity, Hotel B at the airport -- which is under renovation and running at 30% occupancy
-- has idle kitchen capacity, idle housekeeping capacity, and maintenance staff with time
to spare.

In the physical world, you cannot move Hotel B's kitchen to Hotel A. But in a distributed
AI system, you can. If the kitchen ensemble is a service that accepts work requests over a
network, it does not matter which cluster the request comes from. Hotel A's room service
can delegate "prepare-meal" to Hotel B's kitchen, and the guest in Hotel A gets their
sandwich.

This is **federation**: multiple independent realms (hotels) that share capabilities
across boundaries.

## Realms

A realm is a trust and discovery boundary. In Kubernetes terms, a realm maps to a namespace
(or a cluster). Ensembles within a realm discover each other via K8s DNS and communicate
directly.

```
Federation: "Hotel Chain"
  +-- Realm: hotel-downtown  (K8s namespace or cluster)
  |     +-- kitchen
  |     +-- room-service
  |     +-- maintenance
  |     +-- procurement
  |
  +-- Realm: hotel-airport
  |     +-- kitchen (idle capacity)
  |     +-- housekeeping (idle capacity)
  |     +-- maintenance (busy with renovation)
  |
  +-- Realm: hotel-beach
        +-- kitchen
        +-- room-service
        +-- maintenance
```

Within a realm, discovery is automatic (K8s DNS). Across realms, discovery requires
configuration: which realms are in the federation, what gateway endpoints connect them,
and what authentication/authorization governs cross-realm requests.

## Capacity Advertisement

For cross-realm routing to work, ensembles must advertise their capacity -- not just their
capabilities but their current availability:

```json
{
  "type": "capacity_update",
  "ensemble": "kitchen",
  "realm": "hotel-airport",
  "status": "available",
  "currentLoad": 0.2,
  "maxConcurrent": 10,
  "availableCapacity": 8,
  "shareable": true
}
```

The `shareable` flag is critical. It means: "I have capacity available for cross-realm
requests." During normal operations, a hotel's kitchen might set `shareable: false` --
its capacity is reserved for its own guests. During renovation (low occupancy), it sets
`shareable: true` -- spare capacity is offered to the federation.

Capacity updates are broadcast periodically (every 30 seconds, configurable). They are
advisory, not commitments. By the time a cross-realm request arrives, the capacity may
have changed. The receiving ensemble handles this gracefully: accept and queue (bend,
don't break), or reject if at hard limits.

## Routing Hierarchy

When an ensemble needs a capability (e.g., "prepare-meal"), the routing hierarchy is:

1. **Local**: Is there a provider in my ensemble? (agent delegation, existing v2.x)
2. **Realm**: Is there a provider in my realm? (same K8s namespace, cross-ensemble)
3. **Federation**: Is there a provider in another realm? (cross-namespace/cross-cluster)

Each level has its own routing strategy:

- **Local**: direct (no network)
- **Realm**: K8s DNS + direct WebSocket or queue
- **Federation**: gateway endpoints, potentially with higher latency and stricter
  authentication

The caller does not explicitly choose the level. The `NetworkTask`/`NetworkTool`
implementation resolves the target at request time:

```java
// The caller just says "kitchen"
NetworkTask.from("kitchen", "prepare-meal")
```

If a local kitchen ensemble is available, it handles the request. If the local kitchen is
at capacity and a federated kitchen has spare capacity, the request routes to the federated
provider. The caller's agent does not know or care where the meal is being prepared.

## When to Federate

Federation is not for every scenario. It introduces cross-realm latency, authentication
overhead, and operational complexity. It is valuable in specific situations:

**Elastic overflow**: The primary provider is at capacity during a peak event. The
federation provides burst capacity from an idle realm.

**Disaster recovery**: The primary provider is down (datacenter outage, major failure).
Another realm can serve requests while the primary recovers.

**Geographic distribution**: Work is routed to the nearest realm with capacity. A guest in
the airport hotel gets their meal from the airport hotel's kitchen, not the downtown
hotel's kitchen.

**Specialization**: One realm has a capability that others do not. The downtown hotel has a
five-star restaurant kitchen with a pastry chef. The airport hotel does not. Cross-realm
delegation to the specialized kitchen is the only way to fulfill the request.

## Cross-Realm Authentication

Within a realm, authentication is handled by the K8s infrastructure (service mesh mTLS,
network policies). Ensembles in the same namespace trust each other.

Across realms, additional authentication is required. The framework defines an SPI for
cross-realm authentication but does not implement specific mechanisms. The expected
production deployment uses:

- **Service mesh federation** (Istio multi-cluster): mTLS certificates issued by a shared
  CA, automatically verified at the gateway
- **API tokens**: pre-shared tokens validated at the federation gateway
- **OAuth/OIDC**: machine-to-machine tokens issued by a shared identity provider

The WorkRequest envelope carries authentication credentials in a header (never in the
natural language context). The receiving realm's gateway validates credentials before
forwarding the request to the target ensemble.

## The Hotel Chain as a Design Pattern

The federation model is not just a technical feature. It is an organizational design
pattern.

A hotel chain has:
- **Shared brand standards**: All hotels offer the same core capabilities (rooms,
  restaurant, maintenance) with the same quality expectations.
- **Local autonomy**: Each hotel operates independently. The downtown hotel does not need
  permission from headquarters to clean a room.
- **Mutual support**: Hotels help each other during peaks, outages, or special events.
- **Centralized policies**: Pricing, HR, and financial policies are set at the chain level.

This maps to the ensemble network:
- **Shared capability contracts**: All realms implement the same shared task names with
  compatible natural language contracts.
- **Local autonomy**: Each realm operates independently. Its ensembles handle their own
  work without coordinating with other realms.
- **Federated capacity sharing**: Realms share spare capacity during peaks.
- **Network-level policies**: Audit policies, operational profiles, and cost management
  rules can be set at the federation level.

The federation is not a hierarchical control structure. It is a peer network where
independent realms choose to cooperate. Each realm can operate in isolation; the federation
adds resilience and flexibility without creating dependency.
