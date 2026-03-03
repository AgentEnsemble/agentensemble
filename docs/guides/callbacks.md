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

All four methods have default no-op implementations, so you only need to override
the events you care about.

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

```java
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
