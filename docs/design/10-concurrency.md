# 10 - Concurrency and Thread Safety

This document specifies the concurrency model for AgentEnsemble.

## Execution Models

AgentEnsemble supports two execution models depending on the configured `Workflow`:

- **Single-threaded** (`SEQUENTIAL`, `HIERARCHICAL`): all tasks execute on the calling thread, one at a time.
- **Concurrent** (`PARALLEL`): independent tasks execute on Java 21 virtual threads simultaneously.

## Immutability Guarantees

| Component | Immutable? | Mechanism |
|---|---|---|
| `Agent` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `Task` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `TaskOutput` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `EnsembleOutput` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `ToolResult` | Yes | `@Value` (Lombok) -- all fields final, no setters |
| `Workflow` | Yes | Enum (inherently immutable) |
| `TaskDependencyGraph` | Yes | All state set in constructor; edge lists are `List.copyOf()` |

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

### ParallelWorkflowExecutor

**Thread-safe**: Yes. Stateless class. All per-run state is held in local variables (concurrent collections, atomic references, latches). Multiple concurrent calls to `execute()` do not share state.

### TemplateResolver

**Thread-safe**: Yes. Static utility methods with no mutable state. All inputs/outputs are immutable.

### AgentTool Implementations

**Thread-safe**: User's responsibility.

In `SEQUENTIAL` workflow, tools are called from a single thread. In `PARALLEL` workflow, the same tool instance may be called concurrently from multiple virtual threads if shared across agents. Users must ensure tool implementations are thread-safe when using `PARALLEL` workflow with shared tool instances. Alternatively, provide separate tool instances per agent.

### ShortTermMemory

**Thread-safe**: Yes (as of v0.5.0).

Uses `CopyOnWriteArrayList` internally. Concurrent `add()` calls from parallel tasks are safe. `getEntries()` returns an immutable snapshot at the time of the call.

### MemoryContext

**Thread-safe**: Yes (as of v0.5.0).

`record()` delegates to `ShortTermMemory.add()` (thread-safe) and the user-supplied `LongTermMemory.store()` (must be thread-safe for `PARALLEL` workflow). Configuration fields are immutable after construction.

### LongTermMemory

**Thread-safe**: User's responsibility for `PARALLEL` workflow.

`EmbeddingStoreLongTermMemory` delegates to LangChain4j's `EmbeddingStore`, which is generally thread-safe for most providers. Users providing custom `LongTermMemory` implementations must ensure thread safety when using `PARALLEL` workflow.

### TaskDependencyGraph

**Thread-safe**: Yes. All state is set in the constructor and never mutated. The graph can be safely shared and read from multiple threads.

## LLM Thread Safety

LangChain4j's `ChatLanguageModel` implementations are generally thread-safe (they use HTTP clients that support concurrent requests). However, this varies by provider and version.

**Our stance**: AgentEnsemble does not guarantee thread safety of user-provided `ChatLanguageModel` instances. In `PARALLEL` workflow, users should ensure their LLM instances support concurrent access, or provide separate instances per agent (already supported via per-agent `llm` configuration).

## Parallel Execution: PARALLEL Workflow

### Virtual Threads

`ParallelWorkflowExecutor` uses `Executors.newVirtualThreadPerTaskExecutor()` (Java 21 final API). Virtual threads are:
- Lightweight (thousands can run concurrently with minimal OS resources)
- Non-blocking for I/O (LLM HTTP calls do not waste OS threads while waiting)
- Simple programming model (no callbacks or futures complexity for callers)

```java
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    // Root tasks are submitted immediately
    for (Task root : graph.getRoots()) {
        submitTask(root, ...);
    }
    latch.await(); // wait for all tasks to resolve
}
// executor.close() awaits all virtual threads before returning
```

### Dependency Graph Execution Algorithm

`TaskDependencyGraph` builds a DAG from each task's `context` list using identity-based maps (`IdentityHashMap`). The execution algorithm:

1. Compute `pendingDepCounts` per task: the number of in-graph dependencies not yet resolved.
2. Submit all root tasks (pending = 0) to the virtual thread executor immediately.
3. When a task completes (success or failure), decrement the pending count of each dependent.
4. If a dependent's count reaches 0, evaluate whether to submit it or skip it:
   - **FAIL_FAST + failure recorded**: skip.
   - **CONTINUE_ON_ERROR + a dep failed**: skip.
   - **Otherwise**: submit.
5. Skipped tasks cascade: calling `resolveDependent()` recursively for a skipped task ensures its own dependents are also counted down.
6. `CountDownLatch(totalTasks)` -- every task is counted down exactly once (completed, failed, or skipped). When latch reaches 0, `latch.await()` returns.

### MDC Propagation

SLF4J's MDC is thread-local. `ParallelWorkflowExecutor` captures the caller's MDC before forking and restores it in each virtual thread:

```java
// Capture before any tasks are submitted
Map<String, String> callerMdc = MDC.getCopyOfContextMap();

// Inside each virtual thread:
MDC.setContextMap(callerMdc);
MDC.put(MDC_AGENT_ROLE, task.getAgent().getRole());
try {
    // execute task
} finally {
    // restore previous MDC (or clear if null)
    if (prevMdc != null) MDC.setContextMap(prevMdc);
    else MDC.clear();
}
```

This ensures `ensemble.id` is propagated to all virtual threads, and each thread additionally sets `agent.role` for the duration of its task.

### Error Strategies

**FAIL_FAST**: An `AtomicReference<TaskExecutionException>` holds the first failure. Once set, all newly-eligible tasks are skipped rather than submitted. Already-running tasks complete normally (we do not interrupt them, as the LLM calls may be close to completion). After `latch.await()` returns, the stored exception is thrown.

**CONTINUE_ON_ERROR**: Failures are recorded in a synchronized `IdentityHashMap<Task, Throwable>`. Dependents of failed tasks are skipped. Independent tasks continue running. After `latch.await()` returns, if any failures were recorded, a `ParallelExecutionException` is constructed with all successful outputs and all failure causes, and thrown.

### Thread Safety of Shared State in execute()

| State | Type | Thread Safety Mechanism |
|---|---|---|
| `completedOutputs` | `Collections.synchronizedMap(new IdentityHashMap<>())` | Synchronized wrapper |
| `failedTaskCauses` | `Collections.synchronizedMap(new IdentityHashMap<>())` | Synchronized wrapper |
| `pendingDepCounts` | `IdentityHashMap<Task, AtomicInteger>` | Map is read-only after build; `AtomicInteger` for concurrent decrements |
| `firstFailureRef` | `AtomicReference<TaskExecutionException>` | CAS semantics via `compareAndSet` |
| `latch` | `CountDownLatch` | Built-in thread safety |
| `completionOrder` | `Collections.synchronizedList(new LinkedList<>())` | Synchronized wrapper |

Java Memory Model guarantee: `CountDownLatch` establishes a happens-before relationship from all actions prior to `latch.countDown()` to the return of `latch.await()`. This guarantees that all task state written before the latch is fully decremented is visible to the main thread when it reads results after `latch.await()` returns.

## Tool Call Counter

The `toolCallCounter` in `AgentExecutor` uses `AtomicInteger`. This is forward-compatible with concurrent agent execution and has negligible overhead in single-threaded use.
