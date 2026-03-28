# Cross-Ensemble Delegation Example

This example demonstrates two ensembles communicating over WebSocket: a kitchen ensemble
that shares a task and a tool, and a room service ensemble that uses them.

## Kitchen Ensemble (Provider)

```java
// A tool that checks ingredient availability
AgentTool inventoryTool = new AbstractAgentTool() {
    @Override public String name() { return "check-inventory"; }
    @Override public String description() { return "Check ingredient availability"; }
    @Override protected ToolResult doExecute(String input) {
        // In production, this would query a real inventory system
        return ToolResult.success("Available: " + input + " (3 portions)");
    }
};

// Build and start the kitchen ensemble
WebDashboard dashboard = WebDashboard.builder().port(7329).build();

Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", Task.builder()
        .description("Prepare a meal as specified by the guest")
        .expectedOutput("Confirmation with preparation details and estimated time")
        .build())
    .shareTool("check-inventory", inventoryTool)
    .webDashboard(dashboard)
    .build();

kitchen.start(7329);
```

## Room Service Ensemble (Consumer)

```java
// Configure connection to the kitchen
NetworkConfig config = NetworkConfig.builder()
    .ensemble("kitchen", "ws://localhost:7329/ws")
    .build();

NetworkClientRegistry registry = new NetworkClientRegistry(config);

// Build the room service ensemble with network capabilities
EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest request: wagyu steak, medium-rare, room 403")
        .tools(
            NetworkTask.from("kitchen", "prepare-meal", registry),
            NetworkTool.from("kitchen", "check-inventory", registry))
        .build())
    .build()
    .run();

System.out.println(result.lastCompletedOutput().orElseThrow().getRaw());

// Clean up
registry.close();
kitchen.stop();
```

## Testing Without Network

```java
// Use stubs instead of real network connections
StubNetworkTask mealStub = NetworkTask.stub("kitchen", "prepare-meal",
    "Meal prepared: wagyu steak, medium-rare. Ready in 25 minutes.");

StubNetworkTool inventoryStub = NetworkTool.stub("kitchen", "check-inventory",
    "Wagyu beef: 3 portions available");

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest request: wagyu steak, medium-rare, room 403")
        .tools(mealStub, inventoryStub)
        .build())
    .build()
    .run();
```

## Using Recordings for Assertions

```java
RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal");

Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle guest request")
        .tools(recorder)
        .build())
    .build()
    .run();

assertThat(recorder.callCount()).isEqualTo(1);
assertThat(recorder.lastRequest()).contains("wagyu");
```

## Transport SPI

The transport layer is pluggable. The default simple mode uses in-process queues:

```java
// Transport for the kitchen ensemble (bound to its inbox)
Transport kitchenTransport = Transport.websocket("kitchen");

// Build a work request
WorkRequest request = new WorkRequest(
    UUID.randomUUID().toString(),
    "room-service",
    "prepare-meal",
    "Wagyu steak, medium-rare, room 403",
    Priority.NORMAL,
    Duration.ofMinutes(30),
    new DeliverySpec(DeliveryMethod.WEBSOCKET, null),
    null, null, null);

// Room service sends the request to the kitchen's inbox
kitchenTransport.send(request);

// Kitchen receives work from its inbox (blocking)
WorkRequest incoming = kitchenTransport.receive(Duration.ofSeconds(30));

// Kitchen processes the request and delivers the response
WorkResponse response = new WorkResponse(
    incoming.requestId(),
    "COMPLETED",
    "Meal prepared: wagyu steak, medium-rare. Ticket #4071.",
    null,
    25000L);

kitchenTransport.deliver(response);
```

The `RequestQueue` and `ResultStore` SPIs are also available independently:

```java
// In-memory request queue
RequestQueue queue = RequestQueue.inMemory();
queue.enqueue("kitchen", request);
WorkRequest dequeued = queue.dequeue("kitchen", Duration.ofSeconds(10));

// In-memory result store
ResultStore store = ResultStore.inMemory();
store.store("req-42", response, Duration.ofHours(1));
WorkResponse retrieved = store.retrieve("req-42");
```

## Priority Queue

Use `RequestQueue.priority()` to order incoming work by priority with optional aging
to prevent starvation of low-priority requests.

```java
// Create a priority queue with 30-minute aging intervals.
// LOW requests are promoted to NORMAL after 30 min, HIGH after 60 min, CRITICAL after 90 min.
PriorityWorkQueue queue = RequestQueue.priority(AgingPolicy.every(Duration.ofMinutes(30)));

// Enqueue requests with different priorities
queue.enqueue("kitchen", new WorkRequest(
    "req-vip-1", "room-service", "prepare-meal",
    "Wagyu steak for penthouse suite", Priority.CRITICAL,
    null, null, null, null, null));

queue.enqueue("kitchen", new WorkRequest(
    "req-4071", "room-service", "prepare-meal",
    "Club sandwich, room 205", Priority.NORMAL,
    null, null, null, null, null));

// Dequeue returns CRITICAL request first
WorkRequest next = queue.dequeue("kitchen", Duration.ofSeconds(30));
// next.requestId() -> "req-vip-1"

// Get queue status for task_accepted responses
QueueStatus status = queue.queueStatus("kitchen", "req-4071");
// status.queuePosition()       -> 0 (it's next)
// status.estimatedCompletion()  -> PT30S
```

## Related

- [Cross-Ensemble Delegation Guide](../guides/cross-ensemble-delegation.md)
- [Network Testing Guide](../guides/network-testing.md)
- [Long-Running Ensembles Guide](../guides/long-running-ensembles.md)
