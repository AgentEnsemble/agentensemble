# Graphs Design

**Status**: Implemented in v4.0 (this release).

## Why

`Loop` covers bounded iteration of a fixed body. It doesn't cover state-machine
routing where the next node is decided per step from the prior output. The use
cases that demanded this:

- **Tool router** — an `analyze` state decides which tool to call; tools return
  to `analyze`. The back-edge is essential; the routing decision is per-step.
- **Selective feedback** — a `critique` state can route back to `write` on
  failure without re-running expensive upstream research. Loop can't express
  this without including research in the body (re-running unnecessarily) or
  excluding research and losing access to its output.
- **Multi-turn negotiation** — two agents alternate until they agree.

The shared shape: arbitrary back-edges between named states with conditional
routing per step. This is exactly what LangGraph's `add_conditional_edges` and
`add_edge` express, and what AE's DAG-based model could not.

## Goals

- Match LangGraph's expressivity for bounded state machines.
- Stay consistent with AE's posture: declarative builder, validated at build
  time, observable through trace and DAG export.
- Single-threaded state walker: simple, debuggable, matches the state-machine
  mental model.
- One graph per ensemble; mutually exclusive with tasks/loops/phases.

## Non-goals

- **Parallel branches inside a graph** (LangGraph `Send`). Real feature, deferred
  to a follow-up. The single-threaded executor keeps the trace shape clean and
  avoids reasoning about state mutation across concurrent branches in v1.
- **LLM-router sugar** (`graph.llmRouter(state, llm)`). The pure-predicate API
  expresses LLM routing today by making the routing state a Task whose output
  names the next state and using `ctx -> ctx.lastOutput().getRaw().equals("toolA")`
  predicates. Sugar method can be added later if the pattern repeats.
- **Cross-run checkpointing** (LangGraph SQLite/Postgres checkpointers). Feeds
  into the existing memory/durable-transport story; separate PR.
- **Nested graphs** (a Graph state whose Task is itself a Graph). v1 says
  state-Task only.
- **Graph-in-Loop / Loop-in-Graph mixing**. v1 says graph is exclusive at the
  ensemble level.

## Public API

```java
Graph router = Graph.builder()
    .name("agent")
    .state("analyze", analyzeTask)
    .state("toolA",   toolATask)
    .state("toolB",   toolBTask)
    .start("analyze")
    .edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().contains("USE_A"))
    .edge("analyze", "toolB", ctx -> ctx.lastOutput().getRaw().contains("USE_B"))
    .edge("analyze", Graph.END)
    .edge("toolA",   "analyze")
    .edge("toolB",   "analyze")
    .maxSteps(20)
    .build();

Ensemble.builder().graph(router).build().run();
```

Defaults: `maxSteps=50`, `onMaxSteps=RETURN_LAST`, `injectFeedbackOnRevisit=true`.

## Internal design

### `WorkflowNode` interface

`Graph` implements `WorkflowNode` (sibling to `Task` and `Loop`) so future work
can embed graphs in other constructs. v1 only exposes graphs at the top level.

### `GraphExecutor`

Single-threaded state walker. Per step:

1. Apply revision-feedback injection if visit number > 1 (and the state isn't in
   `noFeedbackStates`).
2. Run the state's Task via `SequentialWorkflowExecutor.executeSeeded(...)` —
   reuses the full ensemble pipeline (memory scopes, review gates, deterministic
   handlers, AgentExecutor for LLM tasks).
3. Append a `GraphStep` record to the history.
4. Walk outgoing edges in declaration order; first matching edge wins.
   Unconditional edge (null predicate) always matches.
5. Fire `GraphStateCompletedEvent` to listeners with the routed-to next state.
6. If next state is `Graph.END`, terminate normally. If `step == maxSteps` and
   we haven't terminated, apply `MaxStepsAction` (`RETURN_LAST`, `THROW`, or
   `RETURN_WITH_FLAG`). If no edge matches, throw `GraphNoEdgeMatchedException`
   with the candidate edges listed.

### Standalone vs unify (Q1)

**Decision: standalone.** `Graph` is a peer of `Task`, `Loop`, `Phase`, not a
unified replacement for all of them. Smaller blast radius; existing ensembles
continue to work unchanged. Document `Graph` as the most flexible choice for
new state-machine projects.

The unify-everything-as-graph alternative would have rebuilt SEQUENTIAL as
"linear graph", PARALLEL as "concurrent-branch graph", etc. — months of refactor
that would invalidate every existing ensemble until migration. Not justified by
v1 needs.

### Pure predicates vs LLM routing (Q2)

**Decision: pure predicates only.** A `GraphPredicate` is a `Predicate`-like
interface taking `GraphRoutingContext`. LLM routing is achievable today by
making the routing state a Task whose output names the next state; the predicate
is `ctx -> ctx.lastOutput().getRaw().equals("toolA")`.

Adding a dedicated `.llmRouter(state, llm, options)` sugar method is a follow-up
once the LLM-routing pattern's prevalence justifies the API surface.

### Single-threaded vs parallel branches (Q3)

**Decision: single-threaded in v1.** State machines are inherently sequential
(one current state at a time); the per-step model is what matches the LangGraph
mental model. Parallel branches via `Send`-style fan-out are a follow-up.

### State revisits

States can be visited multiple times. By default the executor rebuilds the state
Task via `Task.withRevisionFeedback(autoFeedback, priorOutput, visitNumber - 1)`
on every visit after the first — same primitive `Loop` uses. Auto-feedback
string: `"Graph state '<name>' visit #N. Prior visit's output is provided above;
refine your response based on it."`. Per-state suppression via
`stateNoFeedback(name, task)` for stateless router states.

### Revisit behaviour and identity

The state Task is rebuilt per visit (when feedback injection applies). The
projected outputs map keys by the **original** state Task instances from
`graph.getStates()`, not the per-visit rebuilt instances — same contract as
`Loop.LoopExecutionResult.projectedOutputs`. This means
`EnsembleOutput.getOutput(originalStateTask)` returns the **last** visit's
output for repeated states. Full per-visit history lives in
`getStateOutputsByName()` and `getGraphHistory()`.

### `Ensemble` integration

`Ensemble.builder().graph(Graph)` is mutually exclusive with `task()`, `loop()`,
`phase()`. `EnsembleValidator.validateGraph()` enforces this and rejects
`Workflow.HIERARCHICAL` ensembles with a graph.

`resolveGraph()` mirrors `resolveLoops`: template-substitute every state Task's
description and expectedOutput, then run each through `resolveAgents` for agent
synthesis. The resolved Graph is rebuilt with the same builder, preserving
edges, start state, maxSteps, onMaxSteps, and noFeedbackStates.

`executeGraph()` dispatches to `GraphExecutor` and assembles an `EnsembleOutput`
with a per-step `taskOutputs` list, an identity-keyed `taskOutputIndex` remapped
to the original state Task instances (so user-side `getOutput(taskInstance)`
works), plus the new graph side channels.

### Trace and viz

`LoopTrace` is matched by `GraphTrace` (one per ensemble, since a graph is
exclusive). Records graph name, start state, termination reason, step count, and
per-step state name + step number.

`DagModel` schema bumps to **1.3**:
- Top-level `mode` field (`null` for legacy DAGs, `"graph"` for graph
  ensembles).
- New `DagGraphEdge` entries on `dag.graphEdges` with `fromStateId`, `toStateId`,
  `conditionDescription`, `unconditional`, and post-execution `fired`.
- New `DagTaskNode.nodeType` values: `"graph-state"` for state nodes,
  `"graph-end"` for the implicit terminal cap.

`agentensemble-viz`:
- `graphLayout.ts` switches dagre to `rankdir: TB` for graph mode (state
  machines look better top-to-bottom). Edges render with `conditionDescription`
  as labels; unconditional edges use dashed strokes; post-execution unfired
  edges grey out.
- `TaskNode.tsx` renders `STATE` and `END` badges for the new node types.

`EnsembleListener.onGraphStateCompleted(GraphStateCompletedEvent)` fires after
every state's Task completes with the routed-to next state. Builder convenience:
`Ensemble.builder().onGraphStateCompleted(handler)`.

## Build-time validation

`Graph.builder().build()` rejects:
- Empty `name`, empty states map.
- `maxSteps < 1`.
- Missing or unknown `start` state.
- Edge with unknown `from` or `to`.
- Edge with `from = Graph.END` (END is terminal).
- Non-`END` state with no outgoing edges (would deadlock).
- Reserved state name `Graph.END`.

`EnsembleValidator`:
- Graph mutually exclusive with tasks/loops/phases.
- `Workflow.HIERARCHICAL` rejected.
- Each state Task needs an LLM source or a deterministic handler.

## Verification

- `GraphBuilderTest` — 19 tests covering every validation rule.
- `GraphExecutorTest` — 9 tests: linear, branching first-match-wins, cyclic
  state machine, all `MaxStepsAction` values, no-edge-matched, predicate
  exception propagation, state revisitation.
- `EnsembleGraphTest` — 10 tests: tool-router with back-edges, selective
  feedback, output / index correctness, listener event, mutual exclusion with
  task / loop / phase, hierarchical rejection, template variables on state
  Tasks, RETURN_WITH_FLAG.
- `DagExporterGraphTest` — 2 tests: pre-execution graph mode export, post-
  execution `fired` and termination metadata.
- Two runnable examples (`runGraphRouter`, `runGraphRetryWithFallback`) — verify
  the headline patterns work end-to-end against deterministic handlers.

## See also

- [21-phase-review.md](21-phase-review.md) — bounded one-step retry primitive.
- [29-loops.md](29-loops.md) — bounded iteration of a fixed body.
