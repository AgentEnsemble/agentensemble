# Chapter 6: Durable Transport

## The Fragility of Direct Connections

WebSocket is a beautiful protocol. Bidirectional, real-time, low-overhead. The existing
AgentEnsemble v2.1.0 live dashboard is built on it: events stream from the JVM to the
browser, review decisions flow from the browser to the JVM, all over a single persistent
connection.

But WebSocket has a fundamental limitation: it is ephemeral. The connection exists between
two specific endpoints -- this pod and that browser tab, this pod and that other pod. When
either endpoint restarts, the connection is gone. When a Kubernetes deployment rolls out
new pods, all connections to the old pods are severed. When a network blip interrupts
connectivity for five seconds, the connection drops.

For the live dashboard, this is manageable. The browser reconnects with exponential backoff,
receives a late-join snapshot, and rebuilds its state. No work is lost because the browser
is a viewer, not a worker.

For cross-ensemble work delivery, it is not manageable. If maintenance sends a "purchase
parts" request to procurement over a WebSocket connection, and procurement's pod restarts
before the request is processed, the request is lost. Maintenance's agent is blocked on a
`CompletableFuture` that will never resolve. The work vanishes.

This is why the Ensemble Network separates the transport into two layers: **real-time**
(WebSocket) for events and human interaction, and **durable** (message queues and result
stores) for reliable work delivery.

## Two Layers, One Protocol

The WorkRequest envelope is the same regardless of transport layer. A `task_request` message
looks identical whether it is sent over WebSocket or enqueued in a Kafka topic. The
separation is about reliability guarantees, not protocol differences.

### Real-Time Layer (WebSocket)

Used for:
- Streaming task progress events to the dashboard
- Human directives and queries
- Review gate requests and decisions
- Capability handshake between ensembles
- Low-latency tool calls when both parties are connected
- Development and local testing

Properties:
- Bidirectional, low latency
- Ephemeral: does not survive pod restarts
- Best-effort delivery: if the connection is down, the message is lost

### Durable Layer (Message Queues + Result Store)

Used for:
- Work request delivery (task_request, tool_request)
- Work result delivery (task_response, tool_response)
- Idempotency cache
- Result caching

Properties:
- Survives pod restarts, network blips, and rolling deployments
- At-least-once delivery (with idempotency for exactly-once semantics)
- Consumer groups for load balancing across replicas
- Persistent: messages are retained until consumed (or TTL expires)

## The Asymmetric Routing Problem

When work delivery uses durable queues, a subtle problem emerges: the pod that processes a
request may be different from the pod that received it.

Consider procurement with 3 replicas. A work request arrives in the queue
`procurement.inbox`. Replica 2 picks it up (consumer group load balancing). Replica 2
processes the request -- its agent finds vendors, compares prices, places the order. Now
replica 2 needs to deliver the result to maintenance.

But replica 2 does not have a WebSocket connection to maintenance. Replica 1 might have
one, or maybe none of the replicas do. The request arrived via queue; the response needs to
go somewhere.

This is why the WorkRequest carries a `DeliverySpec`. The caller explicitly says: "Deliver
the result to `redis://maintenance.results/maint-7721`." Replica 2 writes the result to
the shared result store (Redis) keyed by the request ID. Maintenance reads it from there.

The request path and the response path are completely decoupled. They may use different
transport mechanisms, different infrastructure, and different pods. The `requestId` is the
correlation key that ties them together.

```
Request path:
  Maintenance Pod 1 -> [procurement.inbox queue] -> Procurement Pod 2

Response path:
  Procurement Pod 2 -> [Redis result store: key=maint-7721] -> Maintenance Pod 3
```

Notice that Maintenance Pod 3 (not Pod 1) reads the result. Pod 1 might have been killed
during a deployment rollout. The result is in the shared store; any maintenance replica
can retrieve it.

## The Transport SPI

The transport layer is pluggable. Two implementations are provided:

### Simple Transport (Development)

In-process queues and direct WebSocket. No external infrastructure required. Work requests
are held in a `ConcurrentLinkedQueue` in memory. Results are delivered directly over
WebSocket connections.

```java
EnsembleNetwork.builder()
    .transport(Transport.websocket())
    .build();
```

This is the default for local development. It is fast, simple, and requires no setup. But
it does not survive process restarts, and it does not support horizontal scaling (each JVM
has its own queue).

### Durable Transport (Production)

External request queue and result store. Work requests are enqueued in a durable message
system; results are written to a shared key-value store.

```java
EnsembleNetwork.builder()
    .transport(Transport.durable(
        RequestQueue.redis(redisClient),
        ResultStore.redis(redisClient)))
    .build();
```

Or with Kafka:

```java
EnsembleNetwork.builder()
    .transport(Transport.durable(
        RequestQueue.kafka(kafkaProperties),
        ResultStore.redis(redisClient)))
    .build();
```

The SPI separates the queue (how work is delivered) from the result store (where results are
written). You can mix implementations: Kafka for the queue, Redis for the result store.

### Custom Transport

The SPI is open. If your organization uses a different message system (RabbitMQ, AWS SQS,
Google Pub/Sub), you implement the `RequestQueue` and `ResultStore` interfaces:

```java
public interface RequestQueue {
    void enqueue(String queueName, WorkRequest request);
    WorkRequest dequeue(String queueName, Duration timeout);
    void acknowledge(String queueName, String requestId);
}

public interface ResultStore {
    void store(String requestId, WorkResponse response, Duration ttl);
    WorkResponse retrieve(String requestId);
    void subscribe(String requestId, Consumer<WorkResponse> callback);
}
```

The interfaces are intentionally minimal. They abstract the essential operations without
leaking implementation details.

## Consumer Groups and Horizontal Scaling

When an ensemble has multiple replicas, all replicas read from the same request queue using
consumer groups. This is a standard pattern in both Redis Streams and Kafka:

- The queue has one logical name: `kitchen.inbox`
- Multiple consumers (kitchen pods) read from it
- Each message is delivered to exactly one consumer
- If a consumer dies without acknowledging, the message is redelivered to another consumer

This provides natural load balancing: work is distributed across replicas without any
custom routing logic. K8s scales the pods; the queue distributes the work.

### Visibility Timeout

When a consumer picks up a message from the queue, it has a visibility timeout: a window
during which the message is invisible to other consumers. If the consumer processes the
message and acknowledges it, the message is removed from the queue. If the consumer dies
before acknowledging (pod killed, OOM, crash), the visibility timeout expires and the
message becomes visible again for another consumer to pick up.

This is the mechanism that ensures work is not lost during pod restarts. The request re-
enters the queue and is processed by a healthy replica. The idempotency key in the
WorkRequest ensures that if the original consumer partially processed the request before
dying, the replacement consumer detects the duplicate and either skips it (if the result
was already stored) or re-executes it safely.

## When WebSocket is Enough

Not every cross-ensemble interaction needs durable transport. For quick tool calls between
co-located ensembles that are both healthy, direct WebSocket is faster and simpler.

The `NetworkTool` (shared tool call) defaults to WebSocket transport with a fallback: if
the WebSocket connection is down, queue the request via the durable transport and wait. The
`NetworkTask` (shared task delegation) defaults to durable transport because task executions
are long-running and more vulnerable to pod restarts.

These defaults are configurable. A user who runs everything on a single machine during
development can use WebSocket for everything. A user in production with strict reliability
requirements can force durable transport for all interactions.

## The State of the Art

The split between real-time and durable transport is not novel in distributed systems. It
is the standard architecture for any system that needs both low-latency event streaming
and reliable work processing:

- Trading systems: market data over UDP multicast (real-time), order execution over
  reliable messaging (durable)
- Chat applications: message display over WebSocket (real-time), message persistence in
  a database (durable)
- IoT platforms: telemetry over MQTT (real-time), command delivery over cloud queues
  (durable)

What is novel is applying this pattern to AI agent systems, where the "work" is LLM task
execution and the "events" are agent lifecycle callbacks. The Ensemble Network does not
invent new transport infrastructure. It leverages existing, battle-tested infrastructure
(Redis Streams, Kafka, WebSocket) and provides a clean abstraction over it through the
Transport SPI.
