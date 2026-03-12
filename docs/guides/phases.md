# Phases

A **Phase** is a named group of tasks that forms a logical workstream within an ensemble.
Phases declare dependencies on each other via `after()`. Independent phases execute
in parallel; a phase only starts when all its declared predecessors have completed.

---

## When to Use Phases

Use phases when:

- You have **multiple independent workstreams** that can run concurrently (e.g., gather
  market data and technical data at the same time, then combine them into a report).
- You want to **name stages** of a pipeline for clarity in logs, traces, and dashboards.
- Different stages of work need **different workflow strategies** (e.g., parallel data
  gathering followed by sequential analysis).
- You need a **convergence point** where multiple parallel workstreams must all complete
  before the next stage begins.

Do not use phases when:

- You have a simple linear pipeline. A flat task list with `context()` chaining is
  sufficient and cleaner.
- You need tasks from two workstreams to interleave in a complex dependency pattern.
  Use the parallel workflow with task-level `context()` dependencies instead.

---

## Basic Concepts

### Phase

A phase is an immutable value object containing:

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Unique identifier within the ensemble. Used in logs, traces, and `phaseOutputs`. |
| `tasks` | Yes | One or more tasks that execute within this phase. |
| `workflow` | No | Workflow strategy for internal task execution. Defaults to the ensemble-level workflow. |
| `after` | No | Predecessor phases. This phase will not start until all predecessors complete. |

### Phase DAG

Phases form a directed acyclic graph (DAG). Phases with no `after()` declaration are
**root phases** and start immediately at the beginning of a run. When a phase completes,
AgentEnsemble checks whether any of its dependents now have all predecessors satisfied
and, if so, starts them immediately.

```
Root phases start immediately:    [A]   [B]   [C]
After A and B complete:                         [D depends on A, B]
After D completes:                                                    [E depends on D]
```

### Parallel Execution

Each phase runs on its own virtual thread. Root phases and phases that become unblocked
at the same time run concurrently. The number of concurrent phases is bounded only by
your JVM resources and LLM rate limits.

---

## Declaring Phases

### Static Factory

For simple phases with no workflow override and no dependencies:

```java
Phase research = Phase.of("research", gatherTask, summarizeTask);
```

### Builder

For full control:

```java
Phase research = Phase.builder()
    .name("research")
    .task(gatherTask)
    .task(summarizeTask)
    .workflow(Workflow.PARALLEL)   // tasks within this phase run in parallel
    .build();

Phase writing = Phase.builder()
    .name("writing")
    .after(research)               // writing starts after research completes
    .task(outlineTask)
    .task(draftTask)
    .build();
```

### Multiple Predecessors

A phase can depend on any number of other phases:

```java
Phase report = Phase.builder()
    .name("report")
    .after(marketPhase, technicalPhase, legalPhase)
    .task(reportTask)
    .build();
```

`report` will not start until `marketPhase`, `technicalPhase`, and `legalPhase` have all
completed successfully.

---

## Registering Phases on the Ensemble

```java
Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(phaseA)
    .phase(phaseB)
    .phase(phaseC)
    .build()
    .run();
```

!!! warning "Cannot mix tasks and phases"
    Calling both `.task()` and `.phase()` on the same `Ensemble.builder()` is a
    validation error. Choose one style per ensemble.

Phase declaration order in the builder does not affect execution order -- only the
`after()` relationships determine ordering.

---

## Cross-Phase Context

Tasks in a later phase can reference tasks from an earlier phase using the standard
`Task.context(otherTask)` mechanism. Because the phase DAG guarantees that earlier phases
complete before later phases start, cross-phase context is always safe.

```java
Task gatherTask = Task.of("Gather market data", "Raw market data");

Phase gather = Phase.of("gather", gatherTask);

Phase analyse = Phase.builder()
    .name("analyse")
    .after(gather)
    .task(Task.builder()
        .description("Analyse market trends from gathered data")
        .expectedOutput("Trend analysis with key insights")
        .context(gatherTask)   // references a task from the gather phase
        .build())
    .build();
```

!!! note "Validation"
    AgentEnsemble validates at build time that any cross-phase `context()` reference
    points to a task in a phase that is a declared predecessor (directly or transitively).
    Referencing a task in an unrelated or parallel phase is a `ValidationException`.

---

## Per-Phase Workflow Override

Each phase can use a different internal workflow strategy:

```java
Phase gather = Phase.builder()
    .name("gather")
    .workflow(Workflow.PARALLEL)    // all three fetch tasks run concurrently
    .task(fetchSalesTask)
    .task(fetchInventoryTask)
    .task(fetchCustomerTask)
    .build();

Phase report = Phase.builder()
    .name("report")
    .workflow(Workflow.SEQUENTIAL)  // report tasks depend on each other in order
    .after(gather)
    .task(mergeTask)
    .task(analyseTask)
    .task(writeTask)
    .build();
```

If no `workflow` is set on a phase, it uses the ensemble-level `workflow` (which itself
defaults to `SEQUENTIAL` when no task-level context dependencies are present).

!!! warning "HIERARCHICAL not supported per-phase"
    `Workflow.HIERARCHICAL` cannot be used as a per-phase workflow override. Use it at
    the ensemble level (without phases) for hierarchical delegation.

---

## Error Handling

When a phase fails:

- **Its direct and transitive dependents are skipped.** They will not execute.
- **Independent phases continue running.** A failure in one workstream does not stop
  unrelated workstreams.
- The `EnsembleOutput` will contain a failure record for the failed phase and skip
  records for skipped phases.

```
[A]  [B]  [C]
      |
    fails
      |
     [D]  <-- skipped (depends on B)
                          [E depends on A]  <-- still runs
```

If you need all phases to succeed before proceeding, structure your DAG so that the
final phase depends on all workstreams.

---

## Accessing Phase Outputs

```java
EnsembleOutput output = ensemble.run();

// Backward-compatible flat list of all task outputs
List<TaskOutput> allOutputs = output.getTaskOutputs();

// New: phase-keyed map
Map<String, List<TaskOutput>> byPhase = output.getPhaseOutputs();
List<TaskOutput> researchResults = byPhase.get("research");

// Final output: last task of the last phase in the completed execution
String summary = output.getFinalOutput();
```

---

## Phases and Deterministic Tasks

Phases are compatible with deterministic `handler` tasks. You can mix LLM tasks and
handler tasks within the same phase, or have entire phases that require no LLM at all.

```java
Phase fetch = Phase.builder()
    .name("fetch")
    .task(Task.builder()
        .description("Fetch pricing data")
        .expectedOutput("JSON price map")
        .handler(ctx -> ToolResult.success(priceApi.fetchAll()))
        .build())
    .build();
```

---

## Phases vs. Flat Tasks vs. Parallel Workflow

| Feature | Flat tasks | Parallel workflow | Phases |
|---|---|---|---|
| Multiple concurrent workstreams | No | Inferred from `context()` | Yes, explicit |
| Named grouping of tasks | No | No | Yes |
| Per-group workflow strategy | No | No | Yes |
| Barrier / convergence point | No | Via task dependencies | Yes, via `after()` |
| Cross-group output access | Via `context()` | Via `context()` | Via `context()` |
| Intent clarity | Low for complex DAGs | Medium | High |

Use flat tasks for simple sequential work. Use parallel workflow when the dependency
graph is task-level and fine-grained. Use phases when you think in terms of named
workstreams with clear start/end boundaries.

---

## See Also

- [Workflows guide](workflows.md) -- sequential, parallel, hierarchical
- [Tasks guide](tasks.md) -- task configuration and context chaining
- [Phases examples](../examples/phases.md) -- runnable code examples
- [Ensemble configuration reference](../reference/ensemble-configuration.md)
