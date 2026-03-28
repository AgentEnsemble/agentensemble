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

## Related

- [Long-Running Ensembles](long-running-ensembles.md)
- [Network Testing](network-testing.md)
- [Design Doc: Ensemble Network](../design/24-ensemble-network.md)
