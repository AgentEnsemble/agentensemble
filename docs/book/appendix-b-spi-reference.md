# Appendix B: SPI Reference

The Ensemble Network defines pluggable interfaces (SPIs) for all infrastructure-dependent
components. Users implement these interfaces to integrate with their specific infrastructure.
Default implementations are provided for development (in-memory) and production (Redis,
Kafka).

## Transport Layer

### RequestQueue

Durable queue for work request delivery. Implementations must support consumer groups
(multiple replicas reading from the same queue with at-least-once delivery).

```java
public interface RequestQueue {

    /** Enqueue a work request for a target ensemble. */
    void enqueue(String queueName, WorkRequest request);

    /** Dequeue the next work request, blocking up to the given timeout. Returns null on timeout. */
    WorkRequest dequeue(String queueName, Duration timeout);

    /** Acknowledge successful processing. Removes the message from the queue. */
    void acknowledge(String queueName, String requestId);
}
```

Provided implementations:
- `RequestQueue.inMemory()` -- FIFO `LinkedBlockingQueue`, single-JVM, development only
- `RequestQueue.priority()` -- priority-ordered with FIFO within same level, single-JVM
- `RequestQueue.priority(AgingPolicy)` -- priority-ordered with configurable aging
- `RedisRequestQueue.create(RedisClient)` -- Redis Streams with consumer groups (requires `agentensemble-transport-redis`)
- `RequestQueue.kafka(Properties)` -- Kafka consumer groups (planned)

#### Supporting types

| Type | Description |
|---|---|
| `AgingPolicy` | Configures priority aging. `AgingPolicy.every(Duration)` promotes one level per interval. `AgingPolicy.none()` disables aging. |
| `QueueMetrics` | Callback interface for queue depth reporting. `QueueMetrics.noOp()` discards all reports. |
| `QueueStatus` | Queue position and estimated completion time, returned by `PriorityWorkQueue.queueStatus()`. |

### ResultStore

Shared key-value store for work results. Used for the asymmetric routing pattern where
the response is written by one pod and read by another.

```java
public interface ResultStore {

    /** Store a work response keyed by request ID, with a TTL for automatic cleanup. */
    void store(String requestId, WorkResponse response, Duration ttl);

    /** Retrieve a stored response. Returns null if not found or expired. */
    WorkResponse retrieve(String requestId);

    /** Subscribe for notification when a result for the given request ID is stored. */
    void subscribe(String requestId, Consumer<WorkResponse> callback);
}
```

Provided implementations:
- `ResultStore.inMemory()` -- `ConcurrentHashMap`, single-JVM, development only
- `RedisResultStore.create(RedisClient)` -- Redis with TTL and pub/sub notification (requires `agentensemble-transport-redis`)

### Transport

Factory combining RequestQueue and ResultStore into a transport configuration.

```java
public interface Transport {

    /** Simple mode: in-process queues + direct WebSocket. No external infrastructure. */
    static Transport websocket() { ... }
    static Transport websocket(String ensembleName) { ... }

    /** Durable mode: external queue + external result store. Production-grade. */
    static Transport durable(String ensembleName, RequestQueue queue, ResultStore store) { ... }
    static Transport durable(RequestQueue queue, ResultStore store) { ... }  // "default" name
}
```

## Delivery

### DeliveryHandler

Delivers work responses via the caller-specified delivery method.

```java
public interface DeliveryHandler {

    /** Returns the delivery method this handler supports. */
    DeliveryMethod method();

    /** Deliver the response to the specified address. */
    void deliver(String address, WorkResponse response);
}
```

Provided implementations:
- `WebSocketDeliveryHandler` -- Direct WebSocket send
- `QueueDeliveryHandler` -- Enqueue to a durable queue (Redis Streams, SQS)
- `TopicDeliveryHandler` -- Publish to a topic (Kafka)
- `WebhookDeliveryHandler` -- HTTP POST to a URL
- `StoreDeliveryHandler` -- Write to the ResultStore
- `BroadcastClaimDeliveryHandler` -- Announce to all replicas; first to claim receives
- `NoOpDeliveryHandler` -- Fire and forget (NONE method)

### Ingress

Normalizes work arriving from various sources into WorkRequest envelopes.

```java
public interface Ingress {

    /** Start accepting work. Calls the handler for each received WorkRequest. */
    void start(Consumer<WorkRequest> handler);

    /** Stop accepting work. */
    void stop();
}
```

Provided implementations:
- `WebSocketIngress` -- Receives `task_request`/`tool_request` from WebSocket connections
- `QueueIngress` -- Pulls from a durable queue (Redis Streams, Kafka, SQS)
- `HttpIngress` -- Accepts `POST /api/work` HTTP requests
- `TopicIngress` -- Subscribes to a message topic

## Result Caching

### ResultCache

Optional result caching for repeated queries.

```java
public interface ResultCache {

    /** Store a cached result with the given key and max age. */
    void cache(String cacheKey, WorkResponse response, Duration maxAge);

    /** Retrieve a cached result. Returns null if not found or expired. */
    WorkResponse get(String cacheKey);

    static ResultCache inMemory() { ... }
    static ResultCache redis(RedisClient client) { ... }
}
```

## Shared State

### LockProvider

Distributed locking for shared memory scopes with `Consistency.LOCKED`.

```java
public interface LockProvider {

    /** Acquire a lock on the given scope. Blocks up to timeout. Returns a lock handle. */
    LockHandle acquire(String scope, Duration timeout);

    /** Release a previously acquired lock. */
    void release(LockHandle handle);

    static LockProvider inMemory() { ... }
    static LockProvider redis(RedisClient client) { ... }
}

public interface LockHandle extends AutoCloseable {
    String scope();
    void close();  // releases the lock
}
```

## Observability

### AuditSink

Receives audit records for storage or forwarding.

```java
public interface AuditSink {

    /** Record an audit event. Must be non-blocking (implementations should buffer/batch). */
    void record(AuditRecord record);

    static AuditSink log() { ... }                    // SLF4J structured logging
    static AuditSink database(DataSource ds) { ... }  // JDBC append-only table
    static AuditSink eventStream() { ... }            // Kafka topic
}
```

### ReviewNotifier

Sends out-of-band notifications when a gated review is pending and no qualified human is
connected.

```java
public interface ReviewNotifier {

    /** Send a notification that a review is pending. */
    void notify(ReviewNotification notification);

    static ReviewNotifier slack(String webhookUrl) { ... }
    static ReviewNotifier email(EmailConfig config) { ... }
    static ReviewNotifier webhook(String url) { ... }
}

public record ReviewNotification(
    String reviewId,
    String taskDescription,
    String requiredRole,
    String prompt,
    String dashboardUrl
) {}
```

## Summary

| SPI | Module | Purpose |
|---|---|---|
| `RequestQueue` | agentensemble-network | Durable work request delivery |
| `ResultStore` | agentensemble-network | Shared result storage |
| `Transport` | agentensemble-network | Transport mode factory |
| `DeliveryHandler` | agentensemble-network | Pluggable response delivery |
| `Ingress` | agentensemble-network | Pluggable work ingestion |
| `ResultCache` | agentensemble-network | Optional result caching |
| `LockProvider` | agentensemble-memory | Distributed locking |
| `AuditSink` | agentensemble-network | Audit record storage |
| `ReviewNotifier` | agentensemble-review | Out-of-band review notifications |
