# Long-Running Ensembles

AgentEnsemble v3.0 introduces **long-running mode**: an ensemble that starts, listens for
work, and runs continuously until explicitly stopped. This is the foundation for the
Ensemble Network -- distributed multi-ensemble systems where autonomous ensembles
communicate peer-to-peer.

## One-shot vs. Long-running

| Mode | Description | Example |
|---|---|---|
| **One-shot** (`run()`) | Execute tasks, return output, done. | Research + report generation |
| **Long-running** (`start()`) | Bind a port, accept work, run until stopped. | Kitchen service in a hotel |

The existing `Ensemble.run()` API is completely unchanged.

## Lifecycle States

A long-running ensemble transitions through four states:

```
STARTING -> READY -> DRAINING -> STOPPED
```

| State | Behavior | Accepting work? |
|---|---|---|
| `STARTING` | Binding server port, registering capabilities | No |
| `READY` | Running, accepting and processing work | Yes |
| `DRAINING` | Finishing in-flight work, rejecting new requests | No |
| `STOPPED` | Shutdown complete, connections closed | No |

## Starting and Stopping

```java
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", mealTask)
    .shareTool("check-inventory", inventoryTool)
    .build();

kitchen.start(7329);  // Binds WebSocket server, enters READY state

// ... ensemble runs until stopped ...

kitchen.stop();       // DRAINING -> STOPPED
```

### Idempotency

- Calling `start()` on an already-started ensemble is a no-op.
- Calling `stop()` on an already-stopped or never-started ensemble is a no-op.

### Graceful Shutdown

When `stop()` is called, the ensemble transitions to `DRAINING`, stops the WebSocket server
(if this ensemble owns the dashboard lifecycle), and then transitions to `STOPPED`.

The `drainTimeout` field is available for configuration and will be used by a future
implementation that waits for in-flight tasks to complete before stopping.

A JVM shutdown hook is automatically registered so that SIGTERM triggers graceful shutdown.

```java
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .drainTimeout(Duration.ofMinutes(2))  // Configurable; default: 5 minutes
    .build();
```

## Sharing Tasks and Tools

Long-running ensembles can share capabilities with the network:

### Share a Task

A shared task is a full task that other ensembles can delegate work to:

```java
Task mealTask = Task.builder()
    .description("Prepare a meal as specified")
    .expectedOutput("Confirmation with preparation details and timing")
    .build();

Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTask("prepare-meal", mealTask)
    .build();
```

### Share a Tool

A shared tool is a single tool that other ensembles' agents can invoke remotely:

```java
Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .shareTool("check-inventory", inventoryTool)
    .shareTool("dietary-check", allergyCheckTool)
    .build();
```

### Validation

- Shared capability names must be unique within an ensemble.
- Names must not be null or blank.
- Task/tool references must not be null.

## Capability Handshake

When a client connects to a long-running ensemble via WebSocket, the server sends a
`hello` message that includes the ensemble's shared capabilities. Because
`HelloMessage` uses `@JsonInclude(NON_NULL)`, null fields are omitted from the wire payload:

```json
{
    "type": "hello",
    "ensembleId": "run-abc123",
    "sharedCapabilities": [
        {"name": "prepare-meal", "description": "Prepare a meal as specified", "type": "TASK"},
        {"name": "check-inventory", "description": "Check ingredient availability", "type": "TOOL"}
    ]
}
```

This is **backward compatible** with v2.x clients because `MessageSerializer` configures
Jackson with `FAIL_ON_UNKNOWN_PROPERTIES = false`, so older clients simply ignore the new
`sharedCapabilities` field.

## Related

- [Ensemble Configuration Reference](../reference/ensemble-configuration.md)
- [Design Doc: Ensemble Network](../design/24-ensemble-network.md)