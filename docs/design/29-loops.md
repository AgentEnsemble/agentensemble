# Loops Design

**Status**: Implemented in v3.5.

## Why

AgentEnsemble's workflow model is acyclic: tasks form a DAG via `Task.context()`,
and execution proceeds along that DAG. The defining capability of LangGraph and
similar frameworks — **cycles** — was inexpressible at the workflow layer.

Three patterns repeatedly demanded cycles in real ensembles:

1. **Reflection** — writer drafts, critic reviews, repeat until critic approves.
2. **Retry-until-valid** — generate output, validate, regenerate on failure.
3. **Multi-turn negotiation** — two agents take turns to consensus.

Existing primitives covered fragments of this:

- The per-agent **ReAct tool-calling loop** is internally cyclic, but only within
  one task. It can't express writer→critic ping-pong across tasks.
- `PhaseReview.retryPredecessor` provides bounded one-step retry of a single
  predecessor, but the retry shape is hard-coded — you can't iterate the same
  pair of tasks N times.

The Loop construct fills the middle: bounded, declared iteration over a
sub-ensemble of tasks, expressible at build time and observable at runtime.

## Goals

- Cover ~90% of what users reach for cycles to do (reflection, retry-until-valid,
  debate).
- Stay consistent with AgentEnsemble's existing posture: declarative, validated
  at build time, observable through trace and DAG export.
- Don't break the DAG scheduler, viz, or critical-path analysis.
- Keep the public API minimal: one new `WorkflowNode` subtype, one builder method
  on `Ensemble`.

## Non-goals (deferred)

- **Arbitrary back-edges between tasks.** A LangGraph-style "Task X depends on
  Task Y, but Y can also loop back to X" is not in v1. The escape hatch is
  putting the looping pair inside a `Loop`.
- **Nested loops.** Rejected at build time. The combinatorics of iteration-cap
  multiplication is a footgun, and no v1 use case requires it.
- ~~**Loops concurrent with the parallel task DAG.**~~ **Implemented.** Loops
  are first-class nodes in the parallel dependency graph via the shadow-task
  pattern: each loop is wrapped in a deterministic-handler `Task` whose handler
  invokes the `LoopExecutor` and whose `context()` is the loop's outer-DAG deps.
  The parallel coordinator schedules the shadow on a virtual thread like any
  other Task, so loops with no deps run alongside other roots and loops with
  deps wait for their named upstreams. The shadow's synthetic `TaskOutput` is
  stripped from the visible output during the post-parallel merge and replaced
  with the loop's projected per-body outputs.
- **`Task.context` referencing a `Loop`.** Same root cause: extending
  `Task.context` to accept `WorkflowNode` is a public-API change with broader
  implications. v1 workaround: place post-loop work as the final body task.

## Public API

```java
Loop reflection = Loop.builder()
    .name("reflection")
    .task(writeTask)
    .task(critiqueTask)
    .until(ctx -> ctx.lastBodyOutput().getRaw().contains("APPROVED"))
    .maxIterations(5)
    .build();

Ensemble.builder()
    .task(researchTask)
    .loop(reflection)
    .task(publishTask)
    .build()
    .run();
```

**Ordering rules:**

- `Workflow.SEQUENTIAL` — declared tasks first (in declaration order), then
  declared loops (in declaration order).
- `Workflow.PARALLEL` — task DAG runs concurrently, then loops sequentially.
- `Workflow.HIERARCHICAL` — loops rejected at validation time.

## Internal design

### `WorkflowNode` interface

A single marker interface implemented by both `Task` and `Loop`. Not declared
`sealed` because Lombok's `@Value` on `Task` makes the class final at bytecode
time but not in the source AST during annotation processing — interacting with
the sealed-permits check is brittle. A regular interface preserves the same
intent without the ordering hazard.

`Ensemble.Builder` accepts both via `.task(Task)` (existing) and `.loop(Loop)`
(new). Internally each is stored in its own `@Singular` list; the dispatch
logic combines them into a `List<WorkflowNode>` when calling `executeNodes`.

### `Loop` value class

Lombok `@Value @Builder(toBuilder = true)`. `body` is `@Singular("task")` so
users write `.task(t)` per body task. `context` is `@Singular("context")` for
outer-DAG dependencies (Phase D-1 limitation: not currently honoured by the
PARALLEL scheduler).

Build-time validation:

- Body non-empty.
- `maxIterations >= 1` when set.
- Stop condition required: `until` predicate or positive `maxIterations`.
- Body has no nested `Loop` (type system prevents this; defensive check).
- Body task names/descriptions are unique.
- Body task `context()` references stay within the body — outer-DAG deps belong
  on the `Loop` itself.

### `LoopExecutor`

Takes a `SequentialWorkflowExecutor` (the body runner) at construction. Per
iteration:

1. If iteration > 1 and `injectFeedback` is true, replace the body's first task
   via `Task.withRevisionFeedback(autoFeedback, priorOutput, iter-1)` — the
   same primitive `PhaseReview` uses for phase retry. Subsequent body tasks are
   rebuilt to remap `context()` references to the new instance (same identity-
   rewrite pattern as `Ensemble.resolveAgents`).
2. If `memoryMode == FRESH_PER_ITERATION`, clear all body memory scopes via
   `MemoryStore.clear(scope)`.
3. Run the body via `sequentialExecutor.executeSeeded(iterationBody, ctx, {})`.
4. Index outputs by body-task name (key by `Task.name`, falling back to
   `description` when the body task has no name — required because rebuilt
   tasks aren't identity-equal to the originals).
5. Build a `LoopIterationContext` and evaluate the predicate. Stop on `true`.

After loop termination, project outputs per `LoopOutputMode` and return a
`LoopExecutionResult` containing per-iteration history plus an
`IdentityHashMap<Task, TaskOutput>` keyed by the **original** body task instances
so the outer scheduler can resolve them via the same machinery used for regular
tasks.

### `MemoryStore.clear(scope)`

Added as a non-default abstract method on the SPI to support
`FRESH_PER_ITERATION`. `InMemoryStore` removes the scope from its
`ConcurrentHashMap`. `EmbeddingMemoryStore` throws `UnsupportedOperationException`
with an actionable message — the LangChain4j `EmbeddingStore` SPI does not expose
metadata-filtered deletion, and most underlying vector stores cannot clear by
metadata. The error directs users to either use `MemoryStore.inMemory()` for
loop-affected scopes or stick with `ACCUMULATE`.

### `EnsembleOutput.loopHistory` side channel

Three new fields parallel to `taskOutputIndex`:

- `loopHistory: Map<String, List<Map<String, TaskOutput>>>` — loop name →
  iterations → body task name → output.
- `loopTerminationReasons: Map<String, String>` — `"predicate"` or
  `"maxIterations"`.
- `loopsTerminatedByMaxIterations: Set<String>` — populated only when
  `MaxIterationsAction.RETURN_WITH_FLAG` is configured.

Three convenience accessors: `getLoopHistory(name)`, `getLoopTerminationReason(name)`,
`wasLoopTerminatedByMaxIterations(name)`. All excluded from `equals`/`hashCode`/
`toString` (same as `taskOutputIndex`).

### Trace and viz

`LoopTrace` (in `agentensemble-core`) records per-loop summary:
`iterationsRun`, `maxIterations`, `terminationReason`, `onMaxIterations`,
`outputMode`, `memoryMode`, and per-iteration body-task name lists. Lives on
`ExecutionTrace.loopTraces`.

`DagModel` schema bumped to **1.2**. `DagTaskNode` gains:

- `nodeType = "loop"` for loop super-nodes (alongside existing `"map"`,
  `"reduce"`, `"final-reduce"`, `"direct"`).
- `loopMaxIterations` — the configured cap.
- `loopBody` — nested `DagTaskNode` list rendered as a collapsible sub-DAG.

`DagExporter` emits one super-node per loop, ID-namespaced (`"3"`, `"3.0"`,
`"3.1"`, …) so consumers can unambiguously reference body tasks. The loop
appears at the next parallel-group level after the task DAG and lies on the
critical path under the v1 sequential-after-tasks model.

The viz `TaskNode` component renders `nodeType === "loop"` with a `LOOP ≤N`
badge in the header and a "Body: N tasks (role → role)" summary line.

## Alternatives considered

### Option 2: Generalise `PhaseReview` for N-step cycles

Extend `PhaseReview.retryPredecessor` to bounded N-step rollback:

```java
PhaseReview.builder()
    .reviewTask(reviewTask)
    .retryPredecessors(List.of("research", "outline"))
    .maxRetries(5)
    .build();
```

Less new surface area but tightly coupled to PhaseReview semantics — every
cycle must end at a review task. Reflection loops don't naturally have a
"reviewer" role; the second body task is just a critic. Rejected.

### Option 3: First-class back-edges

```java
Task.builder()
    .description("write")
    .cycleBackTo("research")
    .when(output -> needsMoreData(output))
    .maxIterations(5)
    .build();
```

Maximum flexibility, maximum disruption. Breaks DAG inference, viz, scheduling.
For 90% of cycle use cases, the bounded-loop construct is sufficient and stays
within AE's declarative posture. Deferred indefinitely; revisit only if real
demand surfaces for true LangGraph-style cycles.

## Verification

End-to-end tests cover:

1. Reflection loop where the critic returns `APPROVED` on iteration 3.
2. Retry-until-valid with predicate-driven and max-iterations-driven termination.
3. `MaxIterationsAction.RETURN_LAST` / `THROW` / `RETURN_WITH_FLAG`.
4. Parallel workflow with task DAG + loop, confirming declaration order.
5. Memory modes — `ACCUMULATE` (default no-op) and `FRESH_PER_ITERATION`
   against `MemoryStore.inMemory()` and a stub unsupported store.
6. Every build-time validation rule.
7. DAG round-trip — loop super-node with `nodeType: "loop"`, `loopBody`,
   `loopMaxIterations`.
8. `LoopTrace` populated on `ExecutionTrace`.
9. Full `agentensemble-core` regression: 1999 tests green.
10. Full `agentensemble-devtools` regression: 56 tests green.
11. Full `agentensemble-viz` regression: 379 tests green.

## See also

- [21-phase-review.md](21-phase-review.md) — the existing one-step retry primitive.
- [22-task-reflection.md](22-task-reflection.md) — cross-run prompt improvement.
