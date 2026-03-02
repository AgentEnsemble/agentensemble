# 10 - Concurrency and Thread Safety

This document specifies the concurrency model for AgentEnsemble.

## Phase 1: Single-Threaded Execution

All Phase 1 execution is single-threaded:

- `Ensemble.run()` blocks the calling thread until all tasks complete
- Tasks execute sequentially on the calling thread
- No internal thread pools or async operations
- No shared mutable state during execution

This is the simplest correct model and sufficient for the vast majority of use cases in Phase 1.

## Immutability Guarantees

| Component | Immutable? | Mechanism |
|---|---|---|
| `Agent` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `Task` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `TaskOutput` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `EnsembleOutput` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `ToolResult` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `Workflow` | Yes | Enum (inherently immutable) |

All list fields use `List.of()` or `List.copyOf()` to ensure immutable lists. Even if a user passes a mutable list to a builder, the built object stores an immutable copy.

## Thread Safety by Component

### Domain Objects (Agent, Task, TaskOutput, EnsembleOutput)

**Thread-safe**: Yes, unconditionally.

All domain objects are immutable value objects. They can be freely shared across threads without synchronization.

### Ensemble

**Thread-safe for configuration**: Yes. The `Ensemble` object itself is immutable after construction.

**Thread-safe for `run()`**: Yes, in the sense that calling `run()` concurrently from multiple threads on the same `Ensemble` is safe. Each `run()` invocation creates its own independent execution context with local variables. No state is shared between concurrent `run()` calls.

However, the `ChatLanguageModel` instances referenced by agents may have their own thread safety constraints. See "LLM Thread Safety" below.

### AgentExecutor

**Thread-safe**: Yes. Stateless class. All execution state is held in local variables within the `execute()` method. No instance fields are mutated.

### SequentialWorkflowExecutor

**Thread-safe**: Yes. Stateless class. All execution state is held in local variables.

### TemplateResolver

**Thread-safe**: Yes. Static utility methods with no mutable state. All inputs/outputs are immutable.

### AgentTool Implementations

**Thread-safe**: User's responsibility.

In Phase 1, tools are called from a single thread, so thread safety is not a concern. However, users should be aware that in Phase 2 (parallel execution), tool implementations will need to be thread-safe if the same tool instance is shared across agents.

Recommendation: Document that `AgentTool` implementations should be stateless or thread-safe.

## LLM Thread Safety

LangChain4j's `ChatLanguageModel` implementations are generally thread-safe (they use HTTP clients that support concurrent requests). However, this varies by provider and version.

**Our stance**: AgentEnsemble does not guarantee thread safety of user-provided `ChatLanguageModel` instances. In Phase 1, this is irrelevant (single-threaded). In Phase 2, we will document that users should ensure their LLM instances support concurrent access, or provide separate instances per agent.

## Tool Call Counter

The `toolCallCounter` in `AgentExecutor` uses `AtomicInteger` even in Phase 1. This is forward-compatible with Phase 2 parallel execution and has negligible overhead.

## MDC (Mapped Diagnostic Context) Considerations

SLF4J's MDC is thread-local. In Phase 1 (single-threaded), this works perfectly. In Phase 2 (parallel execution), MDC values will need to be propagated to worker threads. This is a known consideration and will be addressed in Phase 2 design.

## Phase 2+ Considerations

For future parallel workflow execution:

### Virtual Threads (Java 21)

Java 21 virtual threads are ideal for parallel task execution:
- Lightweight (thousands can run concurrently)
- Blocking I/O (LLM HTTP calls) does not waste OS threads
- Simple programming model (no callbacks/futures complexity)

```java
// Future parallel execution sketch:
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    List<StructuredTaskScope.Subtask<TaskOutput>> subtasks = independentTasks.stream()
        .map(task -> scope.fork(() -> agentExecutor.execute(task, context, verbose)))
        .toList();
    scope.join();
    scope.throwIfFailed();
    // Collect results
}
```

### MDC Propagation

For parallel execution, MDC context must be captured before forking and restored in each virtual thread:

```java
Map<String, String> parentMdc = MDC.getCopyOfContextMap();
scope.fork(() -> {
    MDC.setContextMap(parentMdc);
    try {
        return agentExecutor.execute(task, context, verbose);
    } finally {
        MDC.clear();
    }
});
```

### Tool Thread Safety Contract

When parallel execution is introduced:
- `AgentTool` Javadoc will document that implementations must be thread-safe if shared
- Or: each agent gets its own tool instances (copy-on-use)

### ChatLanguageModel Sharing

Options for Phase 2:
1. Document that LLM instances must be thread-safe (most are)
2. Allow per-agent LLM instance configuration (already supported)
3. Provide a thread-safe wrapper if needed
