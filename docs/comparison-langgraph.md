# AgentEnsemble vs LangGraph

## Mental model

**LangGraph** is an explicit state machine. You construct a graph by adding nodes (functions) and edges (transitions). State is a typed dict with reducer functions, and the graph supports cycles — reflection loops, retry-until-good, multi-turn negotiation are all first-class.

**AgentEnsemble** offers four orchestration styles, picked per ensemble:

- **`Task`** — declarative DAG inferred from `context()` dependencies; auto-synthesized agents.
- **`Loop`** — bounded iteration of a fixed body of tasks, until a predicate fires.
- **`Graph`** — state machine with named states, conditional edges, and arbitrary back-edges. **AE's LangGraph equivalent.**
- **`Phase`** — coarse-grained workstreams with cross-phase dependencies.

For state-machine flows, `Graph` is the answer:

```python
# LangGraph
graph.add_node("analyze", analyze_fn)
graph.add_conditional_edges("analyze", route_fn, {"toolA": "toolA", "END": END})
graph.add_edge("toolA", "analyze")
```

```java
// AgentEnsemble
Graph router = Graph.builder()
    .name("agent")
    .state("analyze", analyzeTask)
    .state("toolA", toolATask)
    .start("analyze")
    .edge("analyze", "toolA", ctx -> ctx.lastOutput().getRaw().equals("USE_A"))
    .edge("analyze", Graph.END)
    .edge("toolA", "analyze")            // back-edge
    .build();

Ensemble.builder().graph(router).build().run();
```

## Feature comparison

| | AgentEnsemble | LangGraph |
|---|---|---|
| **Language** | Java 21 (JVM-native) | Python / JS |
| **Topology** | DAG (`context`), Loop, Graph (state machine), Phase | Explicit nodes + edges |
| **Cycles** | `Loop` for bounded iteration; **`Graph` for full state-machine cycles** | Core feature, fully arbitrary |
| **Conditional routing** | `GraphPredicate` (per-edge), Manager LLM, `Loop.until` | `add_conditional_edges` with arbitrary predicates |
| **State** | Immutable `TaskOutput` flow | Typed dict + reducers |
| **Parallel exec** | Auto DAG, virtual threads | Explicit `Send()` API |
| **Supervisor** | Auto-synthesized Manager | User-coded supervisor |
| **Structured output** | Java records, auto-retry | Pydantic |
| **Persistence** | Memory + durable queues | Checkpointing |
| **Human-in-loop** | First-class review gates | Breakpoints |
| **Control plane** | REST + WebSocket built-in | None (use LangGraph Server) |
| **Distributed** | Pull workers + Redis/Kafka | LangGraph Cloud / external |
| **Observability** | ExecutionTrace + Micrometer | LangSmith |

## What AgentEnsemble has that LangGraph doesn't (in-core)

**Control API.** REST + WebSocket server (`agentensemble-web`) — `POST /api/runs`, `POST /api/runs/{id}/cancel`, SSE event stream, model override per task, review-decision endpoints, capability discovery. Origin validation, heartbeats, deferred JSON serialization.

**Network module.** Cross-ensemble delegation via `NetworkTool` and `NetworkTask`. Three request modes: `AWAIT`, `ASYNC`, `AWAIT_WITH_DEADLINE`. Three deadline actions: `RETURN_TIMEOUT_ERROR`, `RETURN_PARTIAL`, `CONTINUE_IN_BACKGROUND`. Lazy WebSocket connection, capability handshake, stub/recording test doubles.

**Three-tier memory.** `EnsembleMemory` combines short-term (per-run accumulator), long-term (vector store via LangChain4j), and entity memory (shared facts). Tasks declare `MemoryScope` for isolation; framework auto-injects matching entries into prompts. Eviction by count or duration.

**Tool sharing.** `ToolCatalog` (immutable, ordered, fail-fast on duplicates) for REST exposure. `NetworkToolCatalog.all(registry)` and `.tagged(tag, registry)` as `DynamicToolProvider` — LLM sees remote tools as local; resolution is fresh per execution, enabling hot-swap.

**Review gates.** Sealed `ReviewDecision` (`Continue` / `Edit(feedback)` / `ExitEarly`). Dual timing — `beforeReview` and `review`. Configurable timeouts and on-timeout actions. Role-based gating. `PhaseReview` adds bounded retry of self or predecessor with feedback injected into the next prompt.

**Bounded loops.** `Loop` workflow construct: a sub-ensemble of tasks repeats until a predicate fires or `maxIterations` is hit. Three output projections, three max-iterations actions, three memory modes (`ACCUMULATE`, `FRESH_PER_ITERATION`, `WINDOW(n)`), automatic revision-feedback injection, per-iteration callbacks, executes as a first-class node in the parallel DAG. See the [Loops guide](guides/loops.md).

**State machines.** `Graph` workflow construct: named states (Tasks) connected by directed edges with conditional `GraphPredicate`s and arbitrary back-edges. Tool routers, selective feedback, multi-turn negotiation. Three max-steps actions, automatic revision-feedback injection on revisits (per-state suppressible), per-step `onGraphStateCompleted` callback, full per-step `getGraphHistory()` side channel, dedicated viz layout (top-to-bottom, conditional edge labels, fired/unfired styling). See the [Graphs guide](guides/graphs.md).

**Durable job queues.** `RequestQueue` / `ResultStore` SPIs with two production transports:
- **Redis** (`agentensemble-transport-redis`) — Redis Streams (`XADD`/`XREADGROUP`/`XACK`/`XAUTOCLAIM`), consumer groups, configurable visibility timeout (~5 min), Pub/Sub for result notifications.
- **Kafka** (`agentensemble-transport-kafka`) — topic-per-queue, consumer groups, manual offset commits.

Pull-based worker model: workers `transport.receive(timeout)` against a named inbox, execute, `transport.deliver(response)`. Idempotency via `requestId`. In-memory and priority-with-aging variants for dev.

## What LangGraph has that AgentEnsemble doesn't

- **Reducer-based state merging.** `operator.add`, custom reducers — useful when parallel branches need to merge into a single state field. AE uses immutable `TaskOutput` chaining instead.
- **Parallel branches inside a state machine** (LangGraph `Send`). AE's `Graph` is single-threaded by design in v1; parallel branches are a planned follow-up.
- **Mature Python ecosystem.** LangChain integrations, LangSmith tracing, LangGraph Cloud deployment, broader community.
- **Streaming token output** out of the box.

## Choosing

**AgentEnsemble** when you are on the JVM, want either DAG-based, loop-based, or state-machine-based orchestration in a single framework, need a built-in HTTP/WebSocket control plane, run distributed workers behind Redis/Kafka, or need first-class human-in-the-loop review gates without bolting on custom code. State-machine cycles are now first-class via `Graph`.

**LangGraph** when your team is Python-native, you need parallel branches inside a state machine (`Send`), you want reducer-based state merging, or you're already invested in the LangChain/LangSmith ecosystem.

**One-line summary.** AgentEnsemble is an opinionated, batteries-included multi-agent runtime for the JVM — control plane, distributed queues, memory, review gates, bounded loops, **state-machine graphs**, and observability are all in the box. LangGraph is a flexible state-machine library for Python — you get parallel branches inside a graph and reducer-based state, but you assemble the production pieces (server, queues, persistence, review UX) yourself or via LangGraph Cloud.
