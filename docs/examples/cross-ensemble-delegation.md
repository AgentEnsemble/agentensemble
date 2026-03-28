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

## Related

- [Cross-Ensemble Delegation Guide](../guides/cross-ensemble-delegation.md)
- [Network Testing Guide](../guides/network-testing.md)
- [Long-Running Ensembles Guide](../guides/long-running-ensembles.md)
