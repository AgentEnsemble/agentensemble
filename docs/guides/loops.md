# Loops

A `Loop` is a bounded iteration over a sub-ensemble of tasks. The body executes in
declared order; after each iteration an optional **predicate** decides whether to
stop. If the predicate doesn't fire, the loop runs at most `maxIterations` times.

Loops cover the patterns you'd reach for cycles in other frameworks:

- **Reflection** — a writer drafts, a critic reviews, repeat until the critic approves.
- **Retry-until-valid** — generate an output, validate it, re-generate on failure.
- **Multi-turn negotiation** — two agents take turns until they agree.

## Quickstart

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workflow.loop.Loop;

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

A `Loop` is a `WorkflowNode` alongside `Task`. The ensemble accepts both via
`.task(Task)` and `.loop(Loop)`.

## Stop conditions

A loop must declare at least one stop condition:

| Condition | Where set | Behaviour |
|---|---|---|
| `until(LoopPredicate)` | `Loop.builder().until(...)` | Evaluated after each iteration. Returning `true` stops. |
| `maxIterations(int)` | `Loop.builder().maxIterations(N)` | Hard cap. Default `5`. |

Both can be set; the loop stops on whichever fires first.

If the cap is reached without the predicate firing, the loop applies its
**termination action** (`onMaxIterations`, default `RETURN_LAST`):

```java
import net.agentensemble.workflow.loop.MaxIterationsAction;

Loop.builder()
    .name("strict")
    .task(generateTask)
    .task(validateTask)
    .until(ctx -> ctx.lastBodyOutput().getRaw().equals("VALID"))
    .maxIterations(3)
    .onMaxIterations(MaxIterationsAction.THROW)   // never-converged is a hard error
    .build();
```

| `MaxIterationsAction` | Behaviour |
|---|---|
| `RETURN_LAST` (default) | Return the last iteration's outputs; ensemble continues normally. |
| `THROW` | Throw `MaxLoopIterationsExceededException`, aborting the ensemble. |
| `RETURN_WITH_FLAG` | Return the last iteration's outputs and set a flag on `EnsembleOutput.wasLoopTerminatedByMaxIterations(name)` so downstream code can react. |

## Output projection

Loops produce one body of outputs per iteration, but only one set of outputs is
exposed to the rest of the ensemble. The projection is controlled by `outputMode`
(default `LAST_ITERATION`):

| `LoopOutputMode` | Outer-DAG visibility |
|---|---|
| `LAST_ITERATION` (default) | One output per body task — the last iteration's value. Matches reflection-loop semantics ("publish the approved draft"). |
| `FINAL_TASK_ONLY` | Only the last body task's last-iteration output. Useful when downstream cares about the writer's text but not the critic's verdict. |
| `ALL_ITERATIONS` | Per body task, a synthesized output whose `raw` text is a `--- iteration N ---` separated concatenation of every iteration. |

The full per-iteration history is **always** available via the side channel
`EnsembleOutput.getLoopHistory(loopName)`, regardless of projection mode.

## Predicate

A `LoopPredicate` is a single-method interface:

```java
@FunctionalInterface
public interface LoopPredicate {
    boolean shouldStop(LoopIterationContext ctx);
}
```

The `LoopIterationContext` exposes:

- `iterationNumber()` — 1-based.
- `lastIterationOutputs()` — `Map<String, TaskOutput>` keyed by body-task `name`
  (or `description` if name is null).
- `history()` — every iteration so far, in iteration order.
- `lastBodyOutput()` — the last task in the body for the current iteration.

The predicate sees only loop-local state by construction; it can't reach tasks
outside the loop body.

## Memory across iterations

Loops integrate with the ensemble's `MemoryStore` via `LoopMemoryMode`:

| `LoopMemoryMode` | Behaviour |
|---|---|
| `ACCUMULATE` (default) | Body-task memory scopes carry across iterations. Required for reflection — the writer needs to see the prior critique. |
| `FRESH_PER_ITERATION` | Body-task memory scopes are cleared between iterations. Useful for retry-until-valid where prior bad outputs would only pollute the next prompt. |
| `WINDOW` | Body-task memory scopes are evicted between iterations to retain only the most-recent N entries. Bounds prompt growth across long-running loops while preserving recent context. Set N via `Loop.builder().memoryWindowSize(N)` (must be `>= 1`). |

`FRESH_PER_ITERATION` requires the configured `MemoryStore` to support
`clear(scope)`. `MemoryStore.inMemory()` does; the embedding-store implementation
does not (vector stores generally cannot delete by metadata filter), and selecting
this mode against an unsupported store throws an actionable
`UnsupportedOperationException` pointing to either `ACCUMULATE` or
`MemoryStore.inMemory()`.

`WINDOW` uses `MemoryStore.evict(scope, EvictionPolicy.keepLastEntries(N))` and is a
no-op on stores that don't support eviction (the embedding-backed store), so the
window is effectively unbounded for vector backends — use `inMemory()` for the
loop's scopes if a strict cap is required.

## Per-iteration callback

Register `Ensemble.builder().onLoopIterationCompleted(handler)` to be notified
after every body iteration finishes (before the predicate is evaluated). Useful
for live dashboards, per-iteration metrics, and progress logging:

```java
Ensemble.builder()
    .loop(reflection)
    .onLoopIterationCompleted(event -> {
        log.info("Loop {} iter {}/{} took {}ms",
            event.loopName(),
            event.iterationNumber(),
            event.maxIterations(),
            event.iterationDuration().toMillis());
    })
    .build()
    .run();
```

The full `LoopIterationCompletedEvent` payload includes the iteration number,
configured cap, per-body-task outputs (keyed by task name), and iteration
duration. Listeners must not block — the loop executor proceeds to predicate
evaluation on the same thread immediately after the event fires.

## Feedback injection

By default (`injectFeedback(true)`), at the start of every iteration after the
first, the loop replaces the body's first task with a copy carrying revision
feedback:

```
Loop iteration 2 of 5. Prior iteration's final body output is shown above; revise based on it.
```

The first task's `revisionFeedback` and `priorAttemptOutput` fields are populated
by `Task.withRevisionFeedback(...)` — the same primitive `PhaseReview` uses for
phase retry. The LLM sees this in its user prompt under a `## Revision Instructions`
section.

Set `injectFeedback(false)` if your body routes feedback through `MemoryScope` or
`Task.context` instead.

Subsequent body tasks are also rebuilt so any `context()` references to the
rebuilt first task are remapped to the new instance. This avoids identity-equality
mismatches that would otherwise trigger a "context task not yet completed" error.

## Workflow compatibility

| Workflow | Loops? | Ordering |
|---|---|---|
| `SEQUENTIAL` | Yes | Tasks execute in declaration order, then loops in declaration order. |
| `PARALLEL` | Yes | Loops are first-class nodes in the dependency DAG. A loop with no `Loop.context()` runs alongside other root tasks. A loop with `Loop.context(taskA, ...)` waits until those tasks complete, then starts in its own virtual thread. Multiple independent loops execute concurrently. |
| `HIERARCHICAL` | **No** | Rejected at validation time. Use `SEQUENTIAL` or `PARALLEL` when declaring loops. |

> **Limitation:** `Task.context` accepts only `Task` instances, so a downstream
> Task cannot directly depend on a Loop's outputs. To put a Task strictly after a
> Loop, place the post-loop work as the final task in the loop body, or split the
> ensemble into Phases with the loop in an upstream phase and the post-loop work
> in a downstream phase.

You also can't mix `loop()` and `phase()` on the same ensemble — pick one
orchestration style.

## Build-time validation

`Loop.builder().build()` rejects:

- Empty body.
- `maxIterations < 1` (when set).
- No stop condition (no predicate **and** no positive `maxIterations`).
- Duplicate body-task names (or descriptions when names are null).
- A body task with `context()` referencing a task outside the loop body. Outer-DAG
  dependencies belong on the `Loop` itself via `Loop.builder().context(...)`.

`EnsembleValidator` adds ensemble-level checks:

- Loop names must be unique within the ensemble.
- Loops cannot be combined with `phases`.
- `Workflow.HIERARCHICAL` rejects loops.
- Each body task must have an LLM source (or a deterministic handler).

## When to use a Loop vs PhaseReview vs in-agent ReAct

- **In-agent ReAct loop** — the tool-calling loop *inside* a single agent's task,
  bounded by `Agent.maxIterations`. Use it when iteration is about deciding which
  tools to call, not about cross-task coordination. No new construct needed.
- **`PhaseReview.retryPredecessor`** — bounded one-step rollback after a review
  task fails. Use when you have an explicit reviewer task and want to re-run a
  single predecessor with reviewer feedback.
- **`Loop`** — multi-task body that repeats N times until a predicate fires. Use
  for reflection, retry-until-valid, and debate patterns.

## Reflection loop walkthrough

```java
Task writer = Task.builder()
    .name("writer")
    .description("Write a 600-word article on {topic}")
    .expectedOutput("A polished article")
    .build();

Task critic = Task.builder()
    .name("critic")
    .description("Critique the article. Reply 'APPROVED' if it meets the bar, otherwise list specific issues.")
    .expectedOutput("APPROVED or a list of issues")
    .context(List.of(writer))
    .build();

Loop reflection = Loop.builder()
    .name("reflection")
    .task(writer)
    .task(critic)
    .until(ctx -> ctx.lastBodyOutput().getRaw().contains("APPROVED"))
    .maxIterations(5)
    .onMaxIterations(MaxIterationsAction.RETURN_LAST)
    .build();

EnsembleOutput out = Ensemble.builder()
    .chatLanguageModel(model)
    .loop(reflection)
    .build()
    .run(Map.of("topic", "edge AI inference"));

// Inspect the projected outputs
TaskOutput finalDraft = out.getOutput(writer).orElseThrow();
String verdict = out.getLoopHistory("reflection").getLast().get("critic").getRaw();

// Did we converge?
boolean converged = out.getLoopTerminationReason("reflection")
    .map("predicate"::equals)
    .orElse(false);
```

## See also

- [PhaseReview](phase-review.md) — quality gates with one-step retry.
- [Task Reflection](task-reflection.md) — cross-run prompt improvement.
- [Workflows](workflows.md) — the broader workflow model.
