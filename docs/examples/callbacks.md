# Example: Callbacks and Event Listeners

This example demonstrates how to observe task and tool execution lifecycle events
using the callback API. It shows both registration styles:

1. **Lambda convenience methods** -- quick inline handlers for individual events
2. **Full `EnsembleListener` implementation** -- a reusable class handling multiple event types

---

## What It Does

A Researcher and Writer run sequentially. Two listeners are registered:

- **`MetricsCollector`** (full interface): observes all four event types, accumulates durations and tool counts, prints a summary at the end
- **Lambda listeners**: a simple progress indicator (`.onTaskStart`) and a failure alerter (`.onTaskFailed`)

Both listeners run for every event -- they accumulate, they don't replace each other.

---

## Approach 1: Lambda Convenience Methods

For quick, one-off handlers, use the builder's lambda methods:

```java
Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onTaskStart(event -> System.out.printf(
        "[PROGRESS] Starting task %d of %d...%n",
        event.taskIndex(), event.totalTasks()))
    .onTaskComplete(event -> System.out.printf(
        "[DONE] %s completed in %s%n",
        event.agentRole(), event.duration()))
    .onTaskFailed(event -> System.out.printf(
        "[ALERT] %s failed: %s%n",
        event.agentRole(), event.cause().getMessage()))
    .onToolCall(event -> System.out.printf(
        "[TOOL] %s called %s%n",
        event.agentRole(), event.toolName()))
    .build()
    .run();
```

Multiple `.onTaskStart()` calls accumulate -- all handlers fire for each event.

---

## Approach 2: Full EnsembleListener Interface

For reusable listeners that handle multiple event types, implement `EnsembleListener`:

```java
class MetricsCollector implements EnsembleListener {

    private final List<String> completedTasks = new ArrayList<>();
    private Duration totalTaskDuration = Duration.ZERO;

    @Override
    public void onTaskStart(TaskStartEvent event) {
        System.out.printf("[METRICS] Task %d/%d started | Agent: %s%n",
                event.taskIndex(), event.totalTasks(), event.agentRole());
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        completedTasks.add(event.agentRole());
        totalTaskDuration = totalTaskDuration.plus(event.duration());
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        System.out.printf("[METRICS] FAILED | Agent: %s | %s%n",
                event.agentRole(), event.cause().getMessage());
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        System.out.printf("[METRICS] Tool: %s | Agent: %s%n",
                event.toolName(), event.agentRole());
    }
}
```

Register it with `.listener()`:

```java
MetricsCollector metrics = new MetricsCollector();

Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .listener(metrics)              // full interface
    .onTaskFailed(this::alertPager) // lambda adds on top
    .build()
    .run();
```

All four methods have default no-op implementations -- override only what you need.

---

## Combining Both Styles

Both styles add to the same listener list. You can mix freely:

```java
Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .listener(new MetricsCollector())       // full interface
    .listener(new AuditLogger())            // another full interface
    .onTaskFailed(event -> alertPager())    // lambda for just this event
    .build()
    .run();
```

---

## Exception Safety

If a listener throws, the exception is caught and logged at WARN level. Execution continues
normally and all subsequent listeners are still called. A misbehaving listener cannot abort
task execution.

---

## Thread Safety

When using `Workflow.PARALLEL`, listener methods may be called concurrently from multiple
virtual threads. Use thread-safe data structures in your listener implementations (e.g.,
`ConcurrentLinkedQueue` instead of `ArrayList`) when collecting events from a parallel workflow.

---

## Delegation Events

When agents delegate to each other, three additional event types are available:
`DelegationStartedEvent`, `DelegationCompletedEvent`, and `DelegationFailedEvent`.
Each event carries a `delegationId` correlation key.

```java
Ensemble.builder()
    .agent(leadResearcher)   // allowDelegation = true
    .agent(writer)
    .task(coordinateTask)
    .onDelegationStarted(event -> System.out.printf(
        "[DELEGATION] %s -> %s [%s]%n",
        event.delegatingAgentRole(), event.workerRole(), event.delegationId()))
    .onDelegationCompleted(event -> System.out.printf(
        "[DELEGATION DONE] %s completed in %s [%s]%n",
        event.workerRole(), event.duration(), event.delegationId()))
    .onDelegationFailed(event -> System.out.printf(
        "[DELEGATION FAILED] %s: %s%n",
        event.workerRole(), event.failureReason()))
    .build()
    .run();
```

For a full `EnsembleListener` that includes delegation events:

```java
class DelegationAuditListener implements EnsembleListener {

    @Override
    public void onDelegationStarted(DelegationStartedEvent event) {
        log.info("Delegation started [{}]: {} -> {} (depth {})",
            event.delegationId(), event.delegatingAgentRole(),
            event.workerRole(), event.delegationDepth());
    }

    @Override
    public void onDelegationCompleted(DelegationCompletedEvent event) {
        log.info("Delegation completed [{}]: {} in {}",
            event.delegationId(), event.workerRole(), event.duration());
    }

    @Override
    public void onDelegationFailed(DelegationFailedEvent event) {
        log.warn("Delegation failed [{}]: {} - {}",
            event.delegationId(), event.workerRole(), event.failureReason());
    }
}
```

Guard violations and policy rejections fire `DelegationFailedEvent` directly, with
`cause() == null`. Worker exceptions carry the thrown exception in `cause()` and
also have a matching `DelegationStartedEvent`.

---

## Running the Example

```bash
export OPENAI_API_KEY=your-key-here
./gradlew :agentensemble-examples:runCallbacks
```

To use a different topic:

```bash
./gradlew :agentensemble-examples:runCallbacks --args="quantum computing"
```

**Full documentation:** [guides/callbacks.md](../guides/callbacks.md)
