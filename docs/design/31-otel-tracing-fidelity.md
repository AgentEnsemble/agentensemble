# 31 - OpenTelemetry Tracing Fidelity

**Status:** Proposed
**Target version:** 3.7.0
**Date:** 2026-08-05

## 1. Motivation

`OTelTracingListener` exports spans at framework boundaries, and `ExecutionTrace` captures
the complete reasoning tree. These are two projections of the same run, but they do not
agree: the structured trace preserves the full hierarchy while the OpenTelemetry projection
flattens it.

The practical consequences for an operator looking at a run in Tempo or Jaeger:

- **The trace is two levels deep regardless of actual structure.** Every span parents to the
  root `ensemble.run` context, so a tool span appears as a sibling of the task that invoked
  it. "What did this task do?" is not answerable from the trace.
- **Tool spans have no width.** They are started and ended in the same statement, with
  elapsed time recorded as an attribute. A waterfall view renders them as zero-duration
  ticks, which is the one visualization a waterfall exists to provide.
- **The ReAct loop is invisible.** No span is created per LLM call, so the reasoning
  iterations -- the structure an operator most wants to inspect when an agent misbehaves --
  are absent from the trace even though `LlmIterationStartedEvent` and
  `LlmIterationCompletedEvent` carry everything needed to build them.
- **Attributes are framework-private.** The `agentensemble.*` namespace is correct for
  framework concepts but does not activate the LLM-aware views in Grafana, Langfuse, Arize
  Phoenix, or other backends that key on the OpenTelemetry GenAI semantic conventions.

This design closes the gap between the two projections, organized as five issues
(OT-001 through OT-005).

## 2. Current State

### 2.1 What Works

| Capability | Implementation |
|------------|----------------|
| Root span per run | `onEnsembleStarted` / `onEnsembleCompleted` -> `ensemble.run` |
| Span per task | `onTaskStart` / `onTaskComplete` / `onTaskFailed` -> `task.execute` |
| Span per tool call | `onToolCall` -> `tool.execute` |
| Span per delegation | `onDelegationStarted` / `Completed` / `Failed` -> `network.delegate` (CLIENT) |
| Trace ID exposed to framework | `getTraceId()` populates `ExecutionTrace.traceId` |
| Cross-process continuity | `TraceContextPropagator` extract/inject of W3C `traceparent` |
| Concurrency safety | `ConcurrentHashMap` for active spans, `volatile` trace ID and root context |
| Listener isolation | Exceptions caught by `ExecutionContext.fire*` dispatch |

### 2.2 Gaps

| Gap | Root cause | Affected code |
|-----|-----------|---------------|
| Tool spans are siblings of tasks, not children | `spanBuilder.setParent(rootContext)` unconditionally | `OTelTracingListener.onToolCall` |
| Delegation spans are siblings of tasks | Same | `OTelTracingListener.onDelegationStarted` |
| Tool spans have zero duration | `startSpan()` immediately followed by `end()` | `OTelTracingListener.onToolCall` |
| Tool outcome not reflected in span status | `ToolCallEvent.outcome()` ignored | `OTelTracingListener.onToolCall` |
| No span per reasoning iteration | `onLlmIterationStarted` / `Completed` not overridden | `OTelTracingListener` |
| LLM iteration events cannot be correlated to a task span | Events carry `agentRole` + `taskDescription` but no `taskIndex` | `LlmIterationStartedEvent`, `LlmIterationCompletedEvent` |
| Delegation events cannot be correlated to a task span | `DelegationStartedEvent` has no `taskIndex` | `DelegationStartedEvent` |
| No `gen_ai.*` attributes | Only `agentensemble.*` keys defined | `OTelAttributes` |
| Model identity absent from telemetry | No event carries the model name | `LlmIteration*Event` |

**Why `Context.current()` does not solve the parenting problem.** The obvious fix -- calling
`Span.makeCurrent()` in `onTaskStart` and relying on implicit context propagation -- does not
work here. OpenTelemetry's current context is a thread-local, and under `PARALLEL` workflows
tasks execute on separate virtual threads; a scope opened on the dispatching thread is not
visible on the executing one. Scoping would also require the listener to hold an open
`Scope` across two callbacks, which leaks if a task never completes. The design below uses
explicit parent keys instead, which is deterministic under any workflow strategy and holds
no thread-affine state.

## 3. Design

### 3.1 OT-001: Task correlation identifiers on iteration and delegation events

**Module:** `agentensemble-core`

Spans can only be parented if the child event knows which task it belongs to. Three events
currently lack that identifier. Add `taskIndex` as a trailing field to each, following the
precedent set by IO-001 (design 27), which added `taskIndex` and `outcome` to
`ToolCallEvent` the same way.

```java
public record LlmIterationStartedEvent(
        String agentRole,
        String taskDescription,
        int iterationIndex,
        List<CapturedMessage> messages,
        int taskIndex) {                    // NEW

    /** Backward-compatible constructor; sets taskIndex to UNKNOWN_TASK_INDEX. */
    public LlmIterationStartedEvent(
            String agentRole, String taskDescription, int iterationIndex,
            List<CapturedMessage> messages) {
        this(agentRole, taskDescription, iterationIndex, messages, UNKNOWN_TASK_INDEX);
    }
}
```

The same treatment applies to `LlmIterationCompletedEvent` and `DelegationStartedEvent`.
`UNKNOWN_TASK_INDEX` is defined as `-1` on a shared constants holder; consumers treat it as
"parent to the run root."

**Emission changes.** `AgentExecutor` already holds the task index in its execution context
when firing tool call events (added by IO-001); the same value is passed when firing
iteration events. Delegation events are fired from `AgentDelegationTool` and
`DelegateTaskTool`, both of which execute within a task's context.

**Backward compatibility.** Records gain trailing fields with a compatibility constructor, so
existing `EnsembleListener` implementations compile and run unchanged. Wire-format consumers
in `agentensemble-web` are updated to pass the value through; `agentensemble-viz` needs no
change because it already keys tool calls by task index.

### 3.2 OT-002: Correct span parenting

**Module:** `agentensemble-telemetry-opentelemetry`

Replace the single `rootContext` field with an explicit registry of parent contexts.

```java
private final ConcurrentHashMap<Integer, Context> taskContexts = new ConcurrentHashMap<>();
private final ConcurrentHashMap<String, Context> delegationContexts = new ConcurrentHashMap<>();
private volatile Context rootContext;
```

`onTaskStart` records the task's context after creating its span:

```java
Span span = spanBuilder.startSpan();
activeSpans.put(taskKey(...), span);
taskContexts.put(event.taskIndex(), Context.root().with(span));
```

Child spans resolve their parent through a single helper, which degrades safely:

```java
private Context parentFor(int taskIndex) {
    Context ctx = taskContexts.get(taskIndex);
    if (ctx != null) {
        return ctx;
    }
    return rootContext != null ? rootContext : Context.root();
}
```

`onTaskComplete` and `onTaskFailed` both remove the task's entry. **Removal on the failure
path is mandatory** -- omitting it leaks one `Context` per failed task for the lifetime of
the listener, which for a long-lived ensemble service is an unbounded leak.

The resulting hierarchy:

```
ensemble.run
├── task.execute (index 0)
│   ├── gen_ai.chat (iteration 0)          [OT-003]
│   │   └── tool.execute
│   ├── gen_ai.chat (iteration 1)
│   └── network.delegate
│       └── (worker ensemble spans, via traceparent)
└── task.execute (index 1)
    └── ...
```

**Nested delegation is explicitly out of scope for this issue.** A worker that delegates
again produces a `DelegationStartedEvent` with `delegationDepth > 0`, but the event carries
no parent delegation identifier, so the span parents to its task rather than to the
enclosing delegation. The tree remains correct, just flatter than reality at depth > 1.
Resolving it requires adding `parentDelegationId` to `DelegationStartedEvent`; tracked
separately rather than expanding this issue.

### 3.3 OT-003: Span per reasoning iteration

**Module:** `agentensemble-telemetry-opentelemetry`

Create a span spanning each LLM call, opened on `onLlmIterationStarted` and closed on
`onLlmIterationCompleted`.

```java
@Override
public void onLlmIterationStarted(LlmIterationStartedEvent event) {
    if (!llmSpansEnabled) {
        return;
    }
    Span span = tracer.spanBuilder(spanName(GenAi.OPERATION_CHAT, modelName))
            .setSpanKind(SpanKind.CLIENT)
            .setParent(parentFor(event.taskIndex()))
            .setAttribute(GenAiAttributes.OPERATION_NAME, GenAi.OPERATION_CHAT)
            .setAttribute(OTelAttributes.AGENT_ROLE, event.agentRole())
            .setAttribute(OTelAttributes.ITERATION_INDEX, (long) event.iterationIndex())
            .startSpan();
    activeSpans.put(iterationKey(event.taskIndex(), event.iterationIndex()), span);
    iterationContexts.put(
            iterationKey(event.taskIndex(), event.iterationIndex()),
            Context.root().with(span));
}
```

On completion the span records usage and the branch the model took:

| Attribute | Source |
|-----------|--------|
| `gen_ai.usage.input_tokens` | `event.inputTokens()` (omitted when negative) |
| `gen_ai.usage.output_tokens` | `event.outputTokens()` (omitted when negative) |
| `agentensemble.llm.response_type` | `event.responseType()` -- `TOOL_CALLS` or `FINAL_ANSWER` |
| `agentensemble.llm.tool_request_count` | `event.toolRequests().size()` |
| `agentensemble.duration_ms` | `event.latency().toMillis()` |

`responseType` as a span attribute is what makes tool-selection drift visible in trace search
without any metrics pipeline: a TraceQL query filtering on `response_type = "FINAL_ANSWER"`
at iteration 0 finds every run where the model answered without consulting a tool.

**Tool span re-parenting.** Once iteration spans exist, tool spans should parent to the
iteration that requested them rather than to the task. `ToolCallEvent` does not carry an
iteration index, so this issue keeps tool spans parented to the task and leaves the
finer-grained attachment to a follow-up that adds `iterationIndex` to `ToolCallEvent`. The
`iterationContexts` map is introduced here so that follow-up is a one-line change.

**Volume.** A ReAct loop runs single-digit iterations per task, so this adds spans
proportional to existing task spans, not to tokens. It is enabled by default, with an opt-out
for users who want the previous span volume:

```java
OTelTracingListener.builder(openTelemetry)
        .llmSpans(false)
        .build();
```

The existing `OTelTracingListener.create(openTelemetry)` factory is retained and delegates to
the builder with defaults.

### 3.4 OT-004: Tool span duration and outcome status

**Module:** `agentensemble-telemetry-opentelemetry`

`onToolCall` fires after the tool has executed, carrying only a `Duration`. Backdate the
start timestamp so the span occupies real width:

```java
Instant end = Instant.now();
Instant start = end.minus(event.duration());

Span span = tracer.spanBuilder("tool.execute")
        .setSpanKind(SpanKind.INTERNAL)
        .setParent(parentFor(event.taskIndex()))
        .setStartTimestamp(start)
        .setAttribute(OTelAttributes.TOOL_NAME, event.toolName())
        .setAttribute(GenAiAttributes.TOOL_NAME, event.toolName())
        .setAttribute(OTelAttributes.AGENT_ROLE, event.agentRole())
        .startSpan();
applyToolOutcome(span, event.outcome());
span.end(end);
```

Using `Instant.now()` as the end timestamp introduces skew equal to the dispatch latency
between tool completion and listener invocation -- sub-millisecond in practice. Eliminating
it entirely would require adding `startedAt`/`completedAt` to `ToolCallEvent`;
`ToolCallTrace` already carries both, so the data exists. Noted as an optional refinement
rather than a prerequisite.

**Outcome mapping.** `ToolCallEvent.outcome()` is a `String` carrying the name of a
`ToolCallOutcome`. The three values map to two span statuses, and the distinction matters:

| Outcome | Span status | Rationale |
|---------|-------------|-----------|
| `SUCCESS` | `StatusCode.OK` | Tool ran, produced a positive result |
| `FAILURE` | `StatusCode.OK` + `agentensemble.tool.outcome=FAILURE` | Tool ran correctly and returned a negative result (no search hits, validation did not pass). Not an error. |
| `ERROR` | `StatusCode.ERROR` | Tool malfunctioned |

Marking `FAILURE` as a span error would make every empty search result look like an outage
and would corrupt error-rate panels built on span status. This mirrors the three-way split
already present in the `ToolMetrics` SPI.

### 3.5 OT-005: GenAI semantic conventions

**Module:** `agentensemble-telemetry-opentelemetry`

Add a `GenAiAttributes` holder alongside `OTelAttributes` and **dual-emit**: framework
concepts keep their `agentensemble.*` keys, and the standard equivalents are added
alongside. Nothing is removed, so existing dashboards continue to work.

| Standard key | Value | Emitted on |
|--------------|-------|-----------|
| `gen_ai.operation.name` | `chat` | iteration spans |
| `gen_ai.provider.name` | provider identifier, when known | iteration spans |
| `gen_ai.request.model` | model identifier, when known | iteration spans |
| `gen_ai.usage.input_tokens` | prompt tokens | iteration spans |
| `gen_ai.usage.output_tokens` | completion tokens | iteration spans |
| `gen_ai.agent.name` | `agentRole` | task, iteration, tool spans |
| `gen_ai.tool.name` | tool name | tool spans |

Span naming for iteration spans follows the convention `{operation} {model}` (for example
`chat claude-opus-4`), falling back to the operation alone when the model is unknown.

**The model identity constraint.** AgentEnsemble is LLM-agnostic through LangChain4j, and
LangChain4j's `ChatModel` interface does not expose a model identifier -- each provider
implementation stores it privately in its builder. There is therefore no way to interrogate
a configured model for its name at runtime.

Three options were considered:

1. Reflectively probe provider implementations for a model field. Rejected: brittle, breaks
   on every provider release, and contradicts the framework's "no reflection tricks"
   principle.
2. Require the model name. Rejected: a breaking change to every builder for a
   telemetry-only concern.
3. Accept an **optional** `modelName(String)` on the `Agent`, `Task`, and `Ensemble`
   builders, used purely for telemetry labelling and resolved with the same precedence as
   model resolution itself (agent, then task, then ensemble).

Option 3 is selected. When unset, `gen_ai.request.model` and `gen_ai.provider.name` are
**omitted entirely** rather than emitted as `unknown`. An absent attribute is honest and
filterable; a placeholder string pollutes group-by results and silently merges distinct
models into one bucket.

This constraint is worth stating in the guide: users who want model-attributed cost and
latency must declare `modelName` explicitly.

## 4. Issue Dependency Graph

```
OT-001 (task correlation IDs)
   ├──> OT-002 (span parenting)
   │        ├──> OT-003 (iteration spans)
   │        └──> OT-004 (tool duration + status)
   └──> OT-003

OT-005 (GenAI semconv) ──> depends on OT-003 for iteration spans to attach to;
                            modelName plumbing is independent and may land first
```

OT-001 is a pure enabler and unblocks everything else. OT-002 and OT-004 are independently
valuable and could ship without OT-003. OT-005's attribute definitions can land at any point;
its `modelName` builder plumbing touches `agentensemble-core` and should be reviewed
separately from the telemetry module changes.

## 5. Testing Strategy

The OpenTelemetry SDK ships `opentelemetry-sdk-testing` with `InMemorySpanExporter`, which
makes span assertions ordinary unit tests. Add it as a `testImplementation` dependency;
`io.opentelemetry:opentelemetry-api` remains `compileOnly` so downstream users who do not
want OTel are unaffected.

### OT-001
- Compatibility constructor sets `taskIndex` to `-1`.
- Full constructor round-trips the supplied index.
- `AgentExecutor` fires iteration events carrying the executing task's index; asserted with
  a capturing listener under both `SEQUENTIAL` and `PARALLEL` workflows.

### OT-002
- Tool span's parent span ID equals the enclosing task span's span ID.
- Under `PARALLEL` with three concurrent tasks, each tool span attaches to its own task --
  the regression test for the virtual-thread case.
- A task that fails removes its context entry: run N failing tasks, assert the internal map
  is empty afterwards.
- A tool event with `taskIndex == -1` parents to the run root without throwing.

### OT-003
- One iteration span per ReAct iteration, in order, with `iterationIndex` ascending.
- Iteration span parents to its task span.
- `responseType` attribute matches the event.
- Negative token counts are omitted, not emitted as `-1`.
- `llmSpans(false)` produces zero iteration spans and does not affect other span types.
- An ensemble that fails mid-iteration does not leave an unended span.

### OT-004
- Span duration is within tolerance of `event.duration()` rather than zero.
- `SUCCESS` and `FAILURE` both yield `StatusCode.OK`; only `ERROR` yields `StatusCode.ERROR`.
- `FAILURE` carries the distinguishing attribute.

### OT-005
- Both `agentensemble.*` and `gen_ai.*` attributes are present on iteration spans.
- With `modelName` unset, neither `gen_ai.request.model` nor `gen_ai.provider.name` appears
  in the attribute set.
- Span name is `chat <model>` when known and `chat` when not.

Coverage must satisfy the module's existing gates: 90% line, 75% branch.

## 6. Design Decisions

**Explicit parent keys over `Context.makeCurrent()`.** Thread-local context does not survive
the virtual-thread boundary in `PARALLEL` workflows, and holding a `Scope` open across two
listener callbacks leaks whenever a task does not complete. Keying by `taskIndex` is
deterministic under every workflow strategy and holds no thread-affine state. The cost is one
map entry per in-flight task.

**Dual-emit rather than migrate to `gen_ai.*`.** Replacing the `agentensemble.*` namespace
would silently break every dashboard and alert rule built against the current attributes. The
duplicated attributes cost a few bytes per span and buy ecosystem compatibility. A future
major version may deprecate the framework-private keys where a standard equivalent exists.

**Omit unknown model rather than emit a placeholder.** `unknown` as an attribute value merges
distinct models into a single group-by bucket and makes the resulting panel confidently wrong.
An absent attribute is visibly absent.

**`FAILURE` is not a span error.** A tool that correctly reports "no results" has not failed
in the observability sense. Conflating it with `ERROR` makes error-rate panels track the
world's contents rather than the system's health -- the distinction the three-way
`ToolMetrics` split already encodes.

**Iteration spans default on.** Span volume grows with reasoning iterations, which are
single-digit per task, not with tokens. The debugging value of seeing the ReAct loop in a
waterfall outweighs a small constant-factor increase in span count. The opt-out exists for
users with strict span budgets.

**Nested delegation parenting deferred.** Correct attachment at `delegationDepth > 1`
requires a new field on `DelegationStartedEvent`. Bundling it here would expand the change
surface across the delegation subsystem for a case that only arises in deep hierarchical
networks; the tree is still correct, only flatter, in the interim.
