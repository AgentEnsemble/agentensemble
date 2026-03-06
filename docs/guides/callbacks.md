# Callbacks and Event Listeners

AgentEnsemble provides an event listener API that lets you observe task and tool execution
lifecycle events without modifying your agent or workflow configuration.

## Quick Start

Register lambda handlers directly on the Ensemble builder:

```java
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onTaskStart(event -> log.info("[{}] Starting: {}", event.taskIndex(), event.taskDescription()))
    .onTaskComplete(event -> log.info("[{}] Done in {}", event.taskIndex(), event.duration()))
    .onTaskFailed(event -> alertService.notify(event.cause()))
    .onToolCall(event -> metrics.increment("tool." + event.toolName()))
    .onDelegationStarted(event -> log.info("Delegating to {} [{}]", event.workerRole(), event.delegationId()))
    .onDelegationCompleted(event -> metrics.record("delegation.latency", event.duration()))
    .onDelegationFailed(event -> log.warn("Delegation failed: {}", event.failureReason()))
    .build()
    .run();
```

## Event Types

### TaskStartEvent

Fired immediately before an agent begins executing a task.

| Field | Type | Description |
|-------|------|-------------|
| `taskDescription()` | `String` | The description of the task |
| `agentRole()` | `String` | The role of the agent executing the task |
| `taskIndex()` | `int` | 1-based index of this task in the workflow run |
| `totalTasks()` | `int` | Total number of tasks in this run |

### TaskCompleteEvent

Fired immediately after an agent successfully completes a task.

| Field | Type | Description |
|-------|------|-------------|
| `taskDescription()` | `String` | The description of the completed task |
| `agentRole()` | `String` | The role of the agent |
| `taskOutput()` | `TaskOutput` | The full output produced by the agent |
| `duration()` | `Duration` | Time from task start to completion |
| `taskIndex()` | `int` | 1-based index of this task |
| `totalTasks()` | `int` | Total number of tasks |

### TaskFailedEvent

Fired when an agent fails to complete a task, before the exception propagates. This lets
you observe failures (for alerting, metrics, etc.) without needing to wrap `run()` in
try-catch.

| Field | Type | Description |
|-------|------|-------------|
| `taskDescription()` | `String` | The description of the failed task |
| `agentRole()` | `String` | The role of the agent |
| `cause()` | `Throwable` | The exception that caused the failure |
| `duration()` | `Duration` | Time from task start to failure |
| `taskIndex()` | `int` | 1-based index of this task |
| `totalTasks()` | `int` | Total number of tasks |

### ToolCallEvent

Fired after each tool execution within an agent's ReAct loop.

| Field | Type | Description |
|-------|------|-------------|
| `toolName()` | `String` | The name of the tool that was called |
| `toolArguments()` | `String` | JSON string of arguments passed to the tool |
| `toolResult()` | `String` | The result returned by the tool |
| `agentRole()` | `String` | The role of the agent that invoked the tool |
| `duration()` | `Duration` | Time taken for the tool execution |

### TokenEvent

Fired for each token received during streaming generation of the final agent response. Only
fires when a `StreamingChatModel` is resolved for the agent (see
[Streaming Output](#streaming-output) below).

| Field | Type | Description |
|-------|------|-------------|
| `token()` | `String` | The text fragment emitted by the streaming model |
| `agentRole()` | `String` | The role of the agent generating the response |

Token events are fired during the direct LLM-to-answer path only. Tool-loop iterations
remain synchronous because the full response must be seen to detect tool-call requests.

### DelegationStartedEvent

Fired immediately before a delegation is handed off to a worker agent. Only fired for
delegations that pass all built-in guards and registered policy evaluations.

| Field | Type | Description |
|-------|------|-------------|
| `delegationId()` | `String` | Unique correlation ID; matches the completed or failed event |
| `delegatingAgentRole()` | `String` | Role of the agent initiating the delegation |
| `workerRole()` | `String` | Role of the agent that will execute the subtask |
| `taskDescription()` | `String` | Description of the subtask |
| `delegationDepth()` | `int` | Depth in the chain (1 = first delegation, 2 = nested, etc.) |
| `request()` | `DelegationRequest` | The full typed delegation request |

### DelegationCompletedEvent

Fired immediately after a delegation completes successfully.

| Field | Type | Description |
|-------|------|-------------|
| `delegationId()` | `String` | Matches the corresponding `DelegationStartedEvent` |
| `delegatingAgentRole()` | `String` | Role of the agent that initiated the delegation |
| `workerRole()` | `String` | Role of the worker that executed |
| `response()` | `DelegationResponse` | Full typed response with output and metadata |
| `duration()` | `Duration` | Elapsed time from delegation start to completion |

### DelegationFailedEvent

Fired when a delegation fails, whether due to a guard violation, policy rejection, or worker
exception. Guard/policy failures have `cause() == null`; worker exceptions carry the thrown
exception. Guard and policy failures do _not_ have a corresponding `DelegationStartedEvent`.

| Field | Type | Description |
|-------|------|-------------|
| `delegationId()` | `String` | Matches `DelegationRequest.getTaskId()` |
| `delegatingAgentRole()` | `String` | Role of the initiating agent |
| `workerRole()` | `String` | Role of the intended target |
| `failureReason()` | `String` | Human-readable description of the failure |
| `cause()` | `Throwable` | Exception if worker threw; `null` for guard/policy failures |
| `response()` | `DelegationResponse` | FAILURE response with error messages |
| `duration()` | `Duration` | Elapsed time from delegation start to failure |

## Full Interface Implementation

For listeners that handle multiple event types, implement `EnsembleListener` directly:

```java
public class MetricsListener implements EnsembleListener {

    private final MeterRegistry registry;

    public MetricsListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onTaskStart(TaskStartEvent event) {
        registry.counter("task.started", "agent", event.agentRole()).increment();
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        registry.timer("task.duration", "agent", event.agentRole())
                .record(event.duration());
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        registry.counter("task.failed", "agent", event.agentRole()).increment();
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        registry.counter("tool.calls", "tool", event.toolName()).increment();
    }
}

// Register with the builder
Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .listener(new MetricsListener(meterRegistry))
    .build()
    .run();
```

All methods have default no-op implementations, so you only need to override
the events you care about. The three delegation event methods (`onDelegationStarted`,
`onDelegationCompleted`, `onDelegationFailed`) are also available on `EnsembleListener`
and can be overridden in the same way.

## Registering Multiple Listeners

All registration methods accumulate -- calling them multiple times adds each listener
to the list. Listeners are called in registration order.

```java
Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .listener(new MetricsListener())      // Full implementation
    .listener(new AuditLogger())          // Another full implementation
    .onTaskFailed(event -> alertPager())  // Lambda for just this one event
    .build()
    .run();
```

## Exception Safety

If a listener throws an exception, the exception is caught and logged at WARN level.
Execution continues normally and all subsequent listeners are still called. A misbehaving
listener cannot abort task execution or prevent other listeners from receiving events.

```java
Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onTaskComplete(event -> {
        // If this throws, it is logged and the next listener still runs
        externalService.send(event);
    })
    .onTaskComplete(event -> {
        // This always runs, even if the previous listener threw
        localLog.record(event);
    })
    .build()
    .run();
```

## Thread Safety

`ExecutionContext` (which carries the listener list) is immutable. When using
`Workflow.PARALLEL`, the `onTaskStart`, `onTaskComplete`, `onTaskFailed`, and `onToolCall`
events may be fired concurrently from multiple virtual threads.

**Listener implementations must be thread-safe when used with a parallel workflow.**

For example, use `ConcurrentLinkedQueue` instead of `ArrayList` if you are collecting
events in a listener:

```java
ConcurrentLinkedQueue<TaskCompleteEvent> events = new ConcurrentLinkedQueue<>();

Ensemble.builder()
    .agents(List.of(a1, a2, a3))
    .tasks(List.of(t1, t2, t3))
    .workflow(Workflow.PARALLEL)
    .onTaskComplete(events::add)  // ConcurrentLinkedQueue is thread-safe
    .build()
    .run();
```

## Practical Examples

### Logging Task Progress

```java
Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onTaskStart(e -> log.info("Task {}/{} starting: {}", e.taskIndex(), e.totalTasks(), e.agentRole()))
    .onTaskComplete(e -> log.info("Task {}/{} done in {}", e.taskIndex(), e.totalTasks(), e.duration()))
    .onTaskFailed(e -> log.error("Task {}/{} failed: {}", e.taskIndex(), e.totalTasks(), e.cause().getMessage()))
    .build()
    .run();
```

### Collecting Metrics

The example below uses `ArrayList`, which is safe for `Workflow.SEQUENTIAL` and
`Workflow.HIERARCHICAL`. For `Workflow.PARALLEL`, callbacks fire from concurrent virtual
threads -- use thread-safe collections instead (see the [Thread Safety](#thread-safety)
section above).

```java
// Safe for sequential/hierarchical workflows. For parallel, replace ArrayList
// with ConcurrentLinkedQueue or CopyOnWriteArrayList.
List<Duration> taskDurations = new ArrayList<>();
List<String> toolsUsed = new ArrayList<>();

Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onTaskComplete(e -> taskDurations.add(e.duration()))
    .onToolCall(e -> toolsUsed.add(e.toolName()))
    .build()
    .run();

log.info("Average task duration: {}", average(taskDurations));
log.info("Tools used: {}", toolsUsed);
```

### Alerting on Failure

```java
Ensemble.builder()
    .agent(researcher)
    .task(criticalTask)
    .onTaskFailed(event -> {
        alertService.sendAlert(
            "Task failed: " + event.taskDescription(),
            "Agent: " + event.agentRole(),
            event.cause()
        );
    })
    .build()
    .run();
```

### Auditing Tool Usage

```java
Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onToolCall(event -> auditLog.record(
        "Agent '" + event.agentRole() + "' called tool '" + event.toolName() + "'" +
        " with args: " + event.toolArguments() +
        " -> result: " + event.toolResult() +
        " (" + event.duration().toMillis() + "ms)"
    ))
    .build()
    .run();
```

## Streaming Output

Agents can stream their final response token-by-token using LangChain4j's
`StreamingChatModel`. When streaming is configured, each token fires an
`onToken(TokenEvent)` callback to all registered listeners.

### Configuration

Streaming is opt-in and off by default. Configure it at one of three levels; the first
non-null value in the chain wins:

| Level | How to set | Priority |
|-------|-----------|---------|
| Agent-level | `Agent.builder().streamingLlm(model)` | Highest |
| Task-level | `Task.builder().streamingChatLanguageModel(model)` | Middle |
| Ensemble-level | `Ensemble.builder().streamingChatLanguageModel(model)` | Lowest |

Only the **final answer path** is streamed. When an agent has tools, the tool-calling
iterations use the synchronous `ChatModel` (full responses are needed to detect tool-call
requests). Streaming fires only when `executeWithoutTools` is active.

### Example: printing tokens to the console

```java
StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(syncModel)              // used for tool-loop iterations
    .streamingChatLanguageModel(streamingModel) // used for final answers
    .task(Task.builder()
        .description("Write a haiku about Java")
        .expectedOutput("A three-line haiku")
        .build())
    .onToken(event -> System.out.print(event.token()))  // typewriter effect
    .build()
    .run();

System.out.println(); // newline after streaming
```

### Example: streaming with the live dashboard

When `webDashboard()` is registered alongside `streamingChatLanguageModel()`, tokens are
broadcast over WebSocket as `token` messages. The viz dashboard displays the text live in
the **Live Output** section of the task detail panel.

```java
WebDashboard dashboard = WebDashboard.onPort(7329);

Ensemble.builder()
    .chatLanguageModel(syncModel)
    .streamingChatLanguageModel(streamingModel)
    .webDashboard(dashboard)
    .task(Task.of("Summarize the state of AI in 2025"))
    .build()
    .run();
```

### Thread safety note

In a parallel workflow, `onToken` may be called concurrently from multiple virtual threads
(one per running agent). Use thread-safe accumulators when collecting tokens across tasks:

```java
ConcurrentHashMap<String, StringBuilder> tokenBuffers = new ConcurrentHashMap<>();

Ensemble.builder()
    .workflow(Workflow.PARALLEL)
    .streamingChatLanguageModel(streamingModel)
    .onToken(event -> tokenBuffers
        .computeIfAbsent(event.agentRole(), k -> new StringBuilder())
        .append(event.token()))
    .tasks(List.of(task1, task2))
    .build()
    .run();
```
