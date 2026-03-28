# Network Testing

AgentEnsemble provides test doubles for `NetworkTask` and `NetworkTool` so you can test
ensembles in isolation without real WebSocket connections.

## Stubs

Stubs return a canned response on every invocation. Use them when you need predictable
behavior from a remote capability:

```java
// Stub a task
StubNetworkTask mealStub = NetworkTask.stub("kitchen", "prepare-meal",
    "Meal prepared: wagyu steak, medium-rare. Estimated 25 minutes.");

// Stub a tool
StubNetworkTool inventoryStub = NetworkTool.stub("kitchen", "check-inventory",
    "3 portions available");

// Use in an ensemble
Ensemble roomService = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle room service request")
        .tools(mealStub, inventoryStub)
        .build())
    .build();

EnsembleOutput result = roomService.run();
```

## Recordings

Recordings capture every request for later assertion. Use them to verify what your
ensemble sends to remote capabilities:

```java
RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal");

Ensemble roomService = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Handle room service request")
        .tools(recorder)
        .build())
    .build();

roomService.run();

// Assert what was sent
assertThat(recorder.callCount()).isEqualTo(1);
assertThat(recorder.lastRequest()).contains("wagyu");
assertThat(recorder.requests()).hasSize(1);
```

### Custom default response

By default, recordings return `"recorded"`. You can provide a custom response:

```java
RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal",
    "Meal prepared in 25 minutes");
```

## Tool naming

Both stubs and recordings use the same `name()` format as real network tools:
`"ensemble.capability"` (e.g., `"kitchen.prepare-meal"`).

## Thread safety

All test doubles are thread-safe. `RecordingNetworkTask` and `RecordingNetworkTool` use
`CopyOnWriteArrayList` internally, so concurrent calls from parallel tool execution are
safely recorded.

## API Reference

### StubNetworkTask / StubNetworkTool

| Method | Description |
|--------|-------------|
| `execute(input)` | Returns the canned response as `ToolResult.success()` |
| `name()` | Returns `"ensemble.task"` format |
| `ensembleName()` | The configured ensemble name |
| `taskName()` / `toolName()` | The configured task/tool name |
| `cannedResponse()` / `cannedResult()` | The configured canned response |

### RecordingNetworkTask / RecordingNetworkTool

| Method | Description |
|--------|-------------|
| `execute(input)` | Records the input and returns the default response |
| `callCount()` | Number of times invoked |
| `lastRequest()` | Most recent input (throws if none) |
| `requests()` | Immutable list of all inputs |
| `name()` | Returns `"ensemble.task"` format |

## Related

- [Cross-Ensemble Delegation](cross-ensemble-delegation.md)
- [Testing Guide](testing.md)
