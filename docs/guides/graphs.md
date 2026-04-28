# Graphs (state machines)

A `Graph` is a state-machine workflow: named states (Tasks) connected by directed
edges with optional conditional predicates. Unlike `Loop` (which iterates a fixed
body until a predicate fires), a `Graph` chooses the next state per step from the
just-completed state's output.

Use a `Graph` for patterns Loop and the DAG can't express:

- **Tool router** — an `analyze` state inspects input and routes to one of several
  tool states; each tool returns to `analyze`; eventually `analyze` terminates.
- **Selective feedback** — a `critique` state can route either forward to publish
  or back to a specific upstream state to retry, without re-running unrelated
  upstream work.
- **Multi-turn negotiation** — two agents take turns until they agree.

## Quickstart

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workflow.graph.Graph;

Graph router = Graph.builder()
    .name("agent")
    .state("analyze", analyzeTask)
    .state("toolA",   toolATask)
    .state("toolB",   toolBTask)
    .start("analyze")
    .edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().contains("USE_A"))
    .edge("analyze", "toolB", ctx -> ctx.lastOutput().getRaw().contains("USE_B"))
    .edge("analyze", Graph.END)              // unconditional fallback
    .edge("toolA",   "analyze")              // back-edge
    .edge("toolB",   "analyze")
    .maxSteps(20)
    .build();

Ensemble.builder()
    .graph(router)
    .build()
    .run();
```

A graph is **exclusive at the ensemble level** — combining `.graph(...)` with
`.task(...)`, `.loop(...)`, or `.phase(...)` is rejected at validation. Pick one
orchestration style per ensemble.

## Routing

After each state's Task completes, the executor walks that state's outgoing edges
in **declaration order**. The first edge whose `GraphPredicate` returns `true`
wins. An edge with a `null` predicate (added via `.edge(String from, String to)`)
is unconditional and always matches — place these last to use as fallbacks.

```java
.edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().equals("USE_A"))
.edge("analyze", "toolB", ctx -> ctx.lastOutput().getRaw().equals("USE_B"))
.edge("analyze", Graph.END)   // catches everything else
```

If no edge matches, the executor throws `GraphNoEdgeMatchedException` with the
candidate edges listed and the just-produced output preview. This typically means
you forgot a fallback — every non-`END` state should have at least one
unconditional edge.

### `GraphPredicate` and `GraphRoutingContext`

```java
@FunctionalInterface
public interface GraphPredicate {
    boolean matches(GraphRoutingContext ctx);
}
```

The `GraphRoutingContext` exposes:

- `currentState()` — name of the state whose Task just completed.
- `lastOutput()` — the just-completed `TaskOutput`.
- `stepNumber()` — 1-based counter, incremented per state visit.
- `stateHistory()` — `Map<String, List<TaskOutput>>` keyed by state name. A state
  visited 3 times has a 3-entry list. Useful for "we've already tried this twice"
  predicates.

## Termination

A graph terminates when:

- An edge routes to `Graph.END` — `getGraphTerminationReason() == "terminal"`.
- The `maxSteps` cap is hit (default `50`) — applies the configured
  `MaxStepsAction`:

| `MaxStepsAction` | Behaviour |
|---|---|
| `RETURN_LAST` (default) | Return the last visited state's output; ensemble continues. |
| `THROW` | Throw `MaxGraphStepsExceededException`. |
| `RETURN_WITH_FLAG` | Return the last output and set `EnsembleOutput.wasGraphTerminatedByMaxSteps()` so downstream code can react. |

## State revisits

The same state can be visited multiple times. By default, on every visit after the
first, the state's `Task` is rebuilt via `Task.withRevisionFeedback(...)` so the
LLM sees an auto-generated revision-instructions section in its prompt with the
prior visit's output. This makes the state's behaviour evolve over visits — a
critique state on its 3rd visit knows about the 2nd visit's verdict.

Suppress per-state via `.stateNoFeedback("router", routerTask)` — useful for
stateless router states whose decision should not be biased by prior visits.
Suppress globally via `.injectFeedbackOnRevisit(false)`.

## Build-time validation

`Graph.builder().build()` rejects:

- Empty `name`, empty states map.
- `maxSteps < 1`.
- Missing `.start(...)`, or start state not in declared states.
- Edge with `from` or `to` referencing an unknown state.
- Edge with `from = Graph.END` (END is terminal — no edges originate).
- A non-`END` state with no outgoing edges (would deadlock).
- Reserved name `Graph.END` (`__END__`) used as a state name.

`EnsembleValidator` adds:

- A graph cannot be combined with `tasks`, `loops`, or `phases`.
- `Workflow.HIERARCHICAL` rejects graphs.
- Each state Task must have an LLM source or a deterministic handler.

## Output and trace

`EnsembleOutput`:

- `getTaskOutputs()` — one entry per visited state in execution order. A state
  visited 3 times produces 3 entries.
- `getOutput(stateTask)` — identity-keyed lookup against the original state Task
  instances; returns the **last** visit's output for repeated states.
- `getGraphHistory()` — the full per-step record, with `stateName`, `stepNumber`,
  `output`, and `nextState` for each step.
- `getGraphTerminationReason()` — `"terminal"` or `"maxSteps"`.
- `wasGraphTerminatedByMaxSteps()` — set when `RETURN_WITH_FLAG` fired.

`ExecutionTrace.graphTrace` — sibling to `loopTraces`. Captures graph name, start
state, termination reason, step count, and per-step state name + step number.

## Per-step callback

Register `Ensemble.builder().onGraphStateCompleted(handler)` to be notified after
every state's Task completes. Useful for live dashboards, per-state metrics, and
progress logging:

```java
Ensemble.builder()
    .graph(router)
    .onGraphStateCompleted(event -> log.info(
        "Step {}/{}: {} → {} ({}ms)",
        event.stepNumber(),
        event.maxSteps(),
        event.stateName(),
        event.nextState(),
        event.stepDuration().toMillis()))
    .build()
    .run();
```

The `GraphStateCompletedEvent` payload includes graph name, current state, step
number, configured cap, the produced output, the routed-to next state, and the
step's wall-clock duration. Listeners must not block — the executor proceeds to
the next state on the same thread immediately after the event fires.

## Visualisation

`DagExporter.build(ensemble)` recognises a graph ensemble and emits a graph-mode
DAG export (schema 1.3). State nodes carry `nodeType: "graph-state"`; the
implicit terminal cap renders as `nodeType: "graph-end"`. Edges live on the
top-level `graphEdges` field with `conditionDescription`, `unconditional`, and
post-execution `fired` flags.

The `agentensemble-viz` dashboard renders graphs top-to-bottom (vs. left-to-right
for legacy DAGs) with conditional edge labels and dashed lines for unconditional
edges. Post-execution exports grey out edges that did not fire, highlighting the
actual path taken.

For post-execution overlays:

```java
EnsembleOutput out = ensemble.run();
DagModel dag = DagExporter.build(graph, out.getTrace().getGraphTrace());
dag.toJson(Path.of("./traces/run.dag.json"));
```

## When to use `Graph` vs `Loop` vs `HIERARCHICAL`

- **`Loop`** — bounded iteration of a fixed body. Reflection (writer + critic) and
  retry-until-valid (generator + validator) are the canonical patterns. The body
  always runs in the same order; the predicate decides only when to stop.
- **`Graph`** — state-machine routing. The next step is decided per iteration
  from the just-completed output. Tool routers, selective feedback, multi-turn
  negotiation. Strictly more expressive than `Loop` but slightly more verbose for
  the simple "writer/critic until approved" case.
- **`Workflow.HIERARCHICAL`** — Manager LLM dispatches to worker agents. Use when
  the routing decision is itself an LLM call and you want the framework to
  synthesize the dispatch logic. Not combinable with graphs.

## Worked example

```java
// Quality-gated publishing pipeline:
//   research → write → critique → publish
//                ^________|       on REJECT
Graph pipeline = Graph.builder()
    .name("pipeline")
    .state("research", researchTask)
    .state("write",    writeTask)
    .state("critique", critiqueTask)
    .state("publish",  publishTask)
    .start("research")
    .edge("research", "write")
    .edge("write",    "critique")
    .edge("critique", "write",
        ctx -> ctx.lastOutput().getRaw().startsWith("REJECT"),
        "REJECT routes back to write only -- research is not re-run")
    .edge("critique", "publish")
    .edge("publish",  Graph.END)
    .maxSteps(10)
    .build();

EnsembleOutput out = Ensemble.builder().graph(pipeline).build().run();

// Inspect what happened
out.getGraphHistory().forEach(step ->
    System.out.printf("step %d: %s → %s%n",
        step.getStepNumber(), step.getStateName(), step.getNextState()));

// Did we converge cleanly?
boolean ok = out.getGraphTerminationReason()
    .map("terminal"::equals)
    .orElse(false);
```

The selective-feedback pattern above is what `Loop` cannot cleanly express: in a
Loop you'd have to either include `research` in the body (re-run on every
iteration, expensive) or only loop `write + critique` with `research` outside the
loop (but then you can't access the loop's projected outputs from `publish`).
`Graph` lets `critique` route back to `write` specifically, retaining
`research`'s output across the retry.

## See also

- [Loops](loops.md) — bounded iteration of a fixed body.
- [Phases](phases.md) — coarse-grained workstreams with cross-phase dependencies.
- [Workflows](workflows.md) — overall workflow strategy.
