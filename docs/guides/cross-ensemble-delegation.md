# Cross-Ensemble Delegation

AgentEnsemble v3.0 enables ensembles to delegate work to each other over WebSocket using
two primitives: **NetworkTask** (full task delegation) and **NetworkTool** (remote tool call).

## NetworkTask: "Hire a Department"

A `NetworkTask` delegates a full task to a remote ensemble. The remote ensemble runs its
complete pipeline -- agent synthesis, ReAct loop, tools, review gates -- and returns the
final output. The calling agent does not know or care about the internal process.

```java
// Room service delegates meal preparation to the kitchen ensemble
Ensemble roomService = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest room service request")
        .tools(NetworkTask.from("kitchen", "prepare-meal", registry))
        .build())
    .build();
```

### Default timeouts

| Setting | Default | Description |
|---------|---------|-------------|
| Connect timeout | 10 seconds | Time to establish WebSocket connection |
| Execution timeout | 30 minutes | Time to wait for task completion |

### Custom timeout

```java
NetworkTask.from("kitchen", "prepare-meal",
    Duration.ofMinutes(15), registry)
```

## NetworkTool: "Borrow a Tool"

A `NetworkTool` invokes a single tool on a remote ensemble. The calling agent retains
control of its reasoning loop -- it just borrows a remote capability for one call.

```java
Ensemble roomService = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest room service request")
        .tools(
            NetworkTool.from("kitchen", "check-inventory", registry),
            NetworkTool.from("kitchen", "dietary-check", registry))
        .build())
    .build();
```

### Default timeout

Tool calls default to 30 seconds (vs. 30 minutes for tasks).

## NetworkClientRegistry

Both `NetworkTask` and `NetworkTool` use a `NetworkClientRegistry` to manage WebSocket
connections. The registry lazily creates and caches connections by ensemble name.

```java
NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://kitchen:7329/ws")
    .ensemble("maintenance", "ws://maintenance:7329/ws")
    .defaultConnectTimeout(Duration.ofSeconds(5))
    .build();

try (NetworkClientRegistry registry = new NetworkClientRegistry(config)) {
    Ensemble roomService = Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.builder()
            .description("Handle room service request")
            .tools(
                NetworkTask.from("kitchen", "prepare-meal", registry),
                NetworkTool.from("kitchen", "check-inventory", registry),
                NetworkTask.from("maintenance", "repair-request", registry))
            .build())
        .build()
        .run();
}
```

## Sharing Capabilities

The remote ensemble must share the tasks and tools that other ensembles want to use:

```java
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", Task.builder()
        .description("Prepare a meal as specified")
        .expectedOutput("Confirmation with preparation details")
        .build())
    .shareTool("check-inventory", inventoryTool)
    .shareTool("dietary-check", allergyCheckTool)
    .webDashboard(WebDashboard.builder().port(7329).build())
    .build();

kitchen.start(7329);
```

## Error Handling

Both `NetworkTask` and `NetworkTool` return `ToolResult.failure()` on errors -- the calling
agent can adapt its behavior based on the error message:

| Scenario | Behavior |
|----------|----------|
| Remote ensemble unreachable | `ToolResult.failure("Network error: ...")` |
| Execution timeout | `ToolResult.failure("Task '...' timed out after ...")` |
| Remote task fails | `ToolResult.failure(error message from remote)` |
| Unknown task/tool name | `ToolResult.failure("Unknown shared task: ...")` |
| Remote ensemble draining | `ToolResult.failure("Ensemble is DRAINING")` |

## Transparency

Both `NetworkTask` and `NetworkTool` implement `AgentTool`. An agent does not know whether
a tool is local or remote. The existing ReAct loop, tool executor, metrics, and tracing
all work unchanged.

## Transport SPI

The transport layer is pluggable via the `Transport` SPI. The default is **simple mode**:
in-process queues with no external infrastructure.

```java
// Transport for the kitchen ensemble (bound to its inbox)
Transport kitchenTransport = Transport.websocket("kitchen");

// Another ensemble sends a work request to the kitchen's inbox
kitchenTransport.send(workRequest);

// Kitchen receives work from its inbox (blocking)
WorkRequest incoming = kitchenTransport.receive(Duration.ofSeconds(30));

// Kitchen processes the request and delivers a response
kitchenTransport.deliver(workResponse);
```

Each transport instance is bound to an ensemble name that identifies its inbox.
`Transport.websocket("kitchen")` creates a transport whose `send()` and `receive()`
both operate on the `"kitchen"` inbox.

### Simple mode

`Transport.websocket(ensembleName)` creates a simple transport backed by in-process
`LinkedBlockingQueue` instances for request delivery and `ConcurrentHashMap` for response
storage. No external infrastructure is required.

This is suitable for local development and testing. It does not survive process restarts
and does not support horizontal scaling.

### Custom transports

The SPI is open for custom implementations. Implement the `Transport` interface to
integrate with your messaging infrastructure:

```java
public class MyCustomTransport implements Transport {
    @Override public void send(WorkRequest request) { /* ... */ }
    @Override public WorkRequest receive(Duration timeout) { /* ... */ }
    @Override public void deliver(WorkResponse response) { /* ... */ }
}
```

The companion `RequestQueue` and `ResultStore` SPIs provide finer-grained abstractions
for the request and response paths independently. Both include `inMemory()` factories
for development.

### Durable mode (Redis)

For production deployments that need to survive pod restarts and support horizontal
scaling, use the Redis-backed transport from the `agentensemble-transport-redis` module:

```gradle
implementation("net.agentensemble:agentensemble-transport-redis:${agentensembleVersion}")
```

```java
RedisClient redisClient = RedisClient.create("redis://localhost:6379");

Transport transport = Transport.durable(
    "kitchen",
    RedisRequestQueue.create(redisClient),
    RedisResultStore.create(redisClient));
```

`RedisRequestQueue` uses Redis Streams with consumer groups for durable, at-least-once
delivery. `RedisResultStore` uses Redis key-value storage with TTL for automatic cleanup
and Pub/Sub for result notifications.

#### Consumer groups for horizontal scaling

When running multiple replicas, each replica uses a different consumer name so that Redis
distributes messages across consumers:

```java
String consumerName = InetAddress.getLocalHost().getHostName();

Transport transport = Transport.durable(
    "kitchen",
    RedisRequestQueue.create(redisClient, consumerName),
    RedisResultStore.create(redisClient));
```

If a consumer crashes before acknowledging a message, the visibility timeout (default
5 minutes) expires and the message is automatically redelivered to a healthy consumer.

See [Chapter 6: Durable Transport](../book/06-durable-transport.md) for a detailed
explanation of the asymmetric routing pattern and consumer group semantics.

## Priority Queue

The `RequestQueue` SPI includes a priority-aware implementation that orders requests by
priority level (CRITICAL > HIGH > NORMAL > LOW) with FIFO ordering within the same level.

### Basic usage

```java
// Priority queue with aging disabled
RequestQueue queue = RequestQueue.priority();
queue.enqueue("kitchen", workRequest);
WorkRequest next = queue.dequeue("kitchen", Duration.ofSeconds(30));
```

### Aging (starvation prevention)

Low-priority requests can be promoted over time to prevent starvation. Configure
an `AgingPolicy` to control how frequently promotions occur:

```java
// Promote unprocessed requests one priority level every 30 minutes
// LOW -> NORMAL (30 min) -> HIGH (60 min) -> CRITICAL (90 min)
RequestQueue queue = RequestQueue.priority(AgingPolicy.every(Duration.ofMinutes(30)));
```

Use `AgingPolicy.none()` to disable aging (the default).

### Queue status for task_accepted responses

The `PriorityWorkQueue` provides queue position and ETA for populating `task_accepted`
messages:

```java
PriorityWorkQueue queue = RequestQueue.priority(
    AgingPolicy.every(Duration.ofMinutes(30)));

queue.enqueue("kitchen", workRequest);

QueueStatus status = queue.queueStatus("kitchen", workRequest.requestId());
// status.queuePosition()       -> 0 (next to be processed)
// status.estimatedCompletion()  -> PT30S (estimated time)
```

### Metrics

Pass a `QueueMetrics` callback to receive queue depth reports after each enqueue/dequeue:

```java
QueueMetrics metrics = new QueueMetrics() {
    private final Map<String, AtomicInteger> depths = new ConcurrentHashMap<>();

    @Override
    public void recordQueueDepth(String queueName, Priority priority, int depth) {
        String key = queueName + ":" + priority.name();
        AtomicInteger gaugeValue = depths.computeIfAbsent(key, k -> {
            AtomicInteger value = new AtomicInteger(depth);
            Gauge.builder("agentensemble.queue.depth", value, AtomicInteger::get)
                .tag("ensemble", queueName)
                .tag("priority", priority.name())
                .register(meterRegistry);
            return value;
        });
        gaugeValue.set(depth);
    }
};
```

## Related

- [Long-Running Ensembles](long-running-ensembles.md)
- [Network Testing](network-testing.md)
- [Design Doc: Ensemble Network](../design/24-ensemble-network.md)
