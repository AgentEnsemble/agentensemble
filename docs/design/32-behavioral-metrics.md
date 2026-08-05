# 32 - Behavioral Metrics

**Status:** Proposed
**Target version:** 3.7.0
**Date:** 2026-08-05

## 1. Motivation

AgentEnsemble emits process metrics -- durations, token counts, tool success and failure
rates. These detect mechanical failure: a tool threw, an API timed out, a run errored.

They do not detect the characteristic agent failure, in which the run succeeds, latency is
normal, no exception is thrown, and the output is wrong. A model upgrade that causes a
research agent to stop calling its search tool and answer from parametric memory *improves*
every existing metric: latency falls because network round-trips disappear, token counts
fall because retrieved context no longer enters the prompt, and the error rate stays at
zero because nothing errored. The agent is now fabricating and the dashboard is green.

What separates the healthy run from the fabricating one is *behavior*: how many reasoning
iterations it took, which tools the model chose, whether it repeated itself, how deep it
delegated. These are observable from events the framework already fires, and none of them
are currently aggregated.

The property that makes behavioral metrics worth building before any evaluation
infrastructure is that **they require no ground truth**. There is no judge model, no labelled
dataset, and no human in the loop. They detect that behavior changed, which in practice
precedes any movement in outcome quality -- often by weeks.

This design adds a behavioral metrics layer as five issues (BM-001 through BM-005).

## 2. Current State

### 2.1 Data Already Flowing

Every signal below is derivable from events the framework fires today:

| Signal | Source event | Field |
|--------|--------------|-------|
| Reasoning iterations per task | `LlmIterationCompletedEvent` | `iterationIndex` |
| Model decision branch | `LlmIterationCompletedEvent` | `responseType` (`TOOL_CALLS` / `FINAL_ANSWER`) |
| Tools the model *chose* | `LlmIterationCompletedEvent` | `toolRequests()` |
| Tools that actually *ran* | `ToolCallEvent` | `toolName`, `outcome` |
| Tool arguments (for repetition detection) | `ToolCallEvent` | `toolArguments` |
| Delegation depth | `DelegationStartedEvent` | `delegationDepth` |
| Per-iteration token usage | `LlmIterationCompletedEvent` | `inputTokens`, `outputTokens` |
| Task boundaries | `TaskStartEvent` / `TaskCompleteEvent` / `TaskFailedEvent` | `taskIndex` |

### 2.2 Gaps

| Gap | Root cause |
|-----|-----------|
| No iteration-count aggregation | No listener consumes `LlmIterationCompletedEvent` for metrics |
| No response-type distribution | `responseType` is carried but never counted |
| No tool-selection distribution | `ToolMetrics` counts executions, not model choices |
| No repetition detection | Nothing compares tool calls within a task |
| No delegation depth distribution | `delegationDepth` is carried but never aggregated |
| No metrics SPI for non-tool signals | `ToolMetrics` is tool-scoped: every method takes a `toolName` |
| No guardrail, review, or output-repair events | `EnsembleListener` has no callbacks for these gates |
| No way to attach deployment labels | No mechanism to tag metrics with `prompt_version` or similar |

**An inconsistency worth resolving alongside BM-004.** `AuditingListener`'s Javadoc states
that the `STANDARD` audit level records "task start/complete/fail, review decisions", but
`EnsembleListener` defines no review callback and `AuditingListener` overrides none. The
documented behavior is not implemented. BM-004 introduces the missing event; the Javadoc
should be corrected either way.

## 3. Design

### 3.1 BM-001: `BehavioralMetrics` SPI

**Module:** `agentensemble-core`, package `net.agentensemble.metrics`

A new interface, following the same pluggable-sink pattern as `ToolMetrics`: the SPI and a
no-op default live in core, backend implementations live in their own modules.

```java
public interface BehavioralMetrics {

    /** Reasoning iterations consumed by a completed task. */
    void recordIterations(String agentRole, String taskName, int iterations);

    /** The branch the model took on one iteration: TOOL_CALLS or FINAL_ANSWER. */
    void incrementResponseType(String agentRole, String responseType);

    /** A tool the model requested. Counts intent, not execution. */
    void incrementToolSelection(String agentRole, String toolName);

    /** Distinct tool-call signatures divided by total tool calls, for one task. */
    void recordNoveltyRatio(String agentRole, String taskName, double ratio);

    /** Delegation depth observed on a handoff. */
    void recordDelegationDepth(String agentRole, int depth);

    /** Outcome of a validation gate. See BM-004. */
    void incrementValidationOutcome(String gate, String agentRole, String outcome);
}
```

`NoOpBehavioralMetrics` provides an empty implementation and is the default, mirroring
`NoOpToolMetrics`. All methods return `void` and must not throw; implementations that wrap a
registry are responsible for their own error containment.

**Why not extend `ToolMetrics`.** Every `ToolMetrics` method takes a `toolName` as its
subject, because the interface models measurements *about a tool*. Iteration counts,
response-type distributions, and delegation depth have no tool. Forcing them through the
existing generic `incrementCounter(metricName, toolName, tags)` escape hatch would require a
synthetic tool name on every call and would make the metric names untyped strings at every
call site. A separate interface keeps both contracts honest.

### 3.2 BM-002: `BehavioralMetricsListener`

**Module:** `agentensemble-core`, package `net.agentensemble.metrics`

An `EnsembleListener` that accumulates per-task state and flushes derived signals at task
boundaries. All computation is backend-agnostic; only the sink is pluggable.

```java
public final class BehavioralMetricsListener implements EnsembleListener {

    private final BehavioralMetrics metrics;
    private final ConcurrentHashMap<Integer, TaskAccumulator> accumulators =
            new ConcurrentHashMap<>();
    ...
}
```

**Event handling.**

| Callback | Action |
|----------|--------|
| `onTaskStart` | Create an accumulator keyed by `taskIndex` |
| `onLlmIterationCompleted` | Increment iteration count; `incrementResponseType`; `incrementToolSelection` for each entry in `toolRequests()` |
| `onToolCall` | Add the call's signature to the accumulator |
| `onDelegationStarted` | `recordDelegationDepth`; track the task's maximum |
| `onTaskComplete` / `onTaskFailed` | Flush `recordIterations` and `recordNoveltyRatio`; remove the accumulator |
| `onEnsembleCompleted` | Clear all accumulators (safety net) |

**Tool selection is measured from `toolRequests()`, not from `ToolCallEvent`.** These differ
whenever a requested tool does not execute -- blocked by a delegation guard, rejected by a
policy, or unavailable. The distinction is diagnostically important: a rising selection count
paired with a flat execution count means the model is repeatedly asking for a capability it
is not being granted, which is invisible if only executions are counted. `ToolMetrics`
continues to own execution outcomes; the two are complementary and should not be merged.

**Accumulator lifecycle and leak safety.** The accumulator map is the one place this listener
can leak. Three guards:

1. Removal happens on **both** `onTaskComplete` and `onTaskFailed`. Omitting the failure path
   leaks one accumulator per failed task, unbounded over the life of a long-running ensemble
   service.
2. `onEnsembleCompleted` clears the map, catching tasks that terminated through a path that
   fired neither callback.
3. The distinct-signature set within an accumulator is capped (default 1,000 entries). Beyond
   the cap, totals keep incrementing but new signatures are not retained, so the reported
   novelty ratio becomes a lower bound. This is the correct direction to be wrong in: a
   thrashing run under-reports its novelty and looks *worse*, never better.

**Thread safety.** Listener callbacks may run concurrently on virtual threads under
`PARALLEL` workflows. The map is a `ConcurrentHashMap`; each accumulator uses atomic counters
and a synchronized signature set. Accumulators are per-task, so contention is limited to the
rare case of concurrent tool calls within a single task.

### 3.3 BM-003: Novelty ratio and call signatures

**Module:** `agentensemble-core`

The novelty ratio detects non-productive iteration -- an agent burning tokens re-invoking the
same tool with equivalent arguments, making no progress. It is defined per task as:

```
novelty = distinct tool-call signatures / total tool calls
```

A value near 1.0 means every action was new. A value below roughly 0.5 means the agent
repeated more than half of its actions.

**Signature construction.**

```
signature = toolName + ":" + sha256(canonicalize(toolArguments)).substring(0, 16)
```

`canonicalize` attempts to parse the arguments as JSON and re-serialize with object keys
sorted, so that `{"a":1,"b":2}` and `{"b":2,"a":1}` produce the same signature. Arguments
that do not parse fall back to a trimmed raw string. Parsing failures are never propagated --
a malformed argument string is hashed as-is.

**Only the truncated hash is retained.** Raw arguments are never stored in the accumulator.
This bounds memory to 16 bytes per distinct call regardless of payload size, and means an
instrumentation path that runs on every request holds no user data. Sixteen hex characters
give a collision probability that is negligible against the per-task call counts involved,
and a collision would only slightly under-report novelty.

**Emission threshold.** The ratio is emitted only when a task made at least two tool calls. A
single-call task has a ratio of exactly 1.0 by construction, and including those datapoints
pulls the distribution toward 1.0 in proportion to how many trivial tasks the workload
contains -- which would mask thrashing in the tasks that actually do work.

### 3.4 BM-004: Validation gate events

**Module:** `agentensemble-core`, `agentensemble-review`

Validation gates are the cheapest source of outcome signal available: every one of them is
already computing a correctness judgment on live traffic and discarding it. Surfacing them
requires new events, since `EnsembleListener` has no callbacks for guardrails, structured
output repair, or review decisions.

```java
public record GuardrailEvaluatedEvent(
        String guardrailName,
        String guardrailType,   // "INPUT" or "OUTPUT"
        String agentRole,
        int taskIndex,
        boolean passed,
        String reason) {}

public record OutputRepairEvent(
        String agentRole,
        int taskIndex,
        int attempt,            // 1-based
        boolean succeeded,
        String failureKind) {}  // e.g. "PARSE_ERROR", "SCHEMA_MISMATCH"

public record ReviewDecisionEvent(
        String agentRole,
        int taskIndex,
        String reviewerId,
        String decision) {}     // "APPROVED", "REJECTED", "REVISED"
```

Corresponding `EnsembleListener` callbacks (`onGuardrailEvaluated`, `onOutputRepair`,
`onReviewDecision`) are added with default no-op bodies, and matching `fire*` methods on
`ExecutionContext`. Emission sites: guardrail evaluation in the executor, the structured
output parse-and-correct loop in `net.agentensemble.output`, and the review gate in
`agentensemble-review`.

`BehavioralMetricsListener` maps all three onto `incrementValidationOutcome`, using the gate
name as the first argument and pass/fail (or the decision) as the outcome.

**`OutputRepairEvent` deserves particular attention.** The rate at which structured output
requires a correction prompt is a direct, continuous measurement of the model's
instruction-following fidelity, computed on live traffic at zero marginal cost. It is among
the earliest indicators of a provider-side model regression, and today the framework performs
the repair silently and reports nothing.

This issue is independently valuable: the events are useful to the live dashboard, to
`ExecutionTrace`, and to audit sinks whether or not the metrics listener consumes them. It
should be reviewable separately from BM-001 through BM-003.

### 3.5 BM-005: Micrometer implementation and wiring

**Module:** `agentensemble-metrics-micrometer`, `agentensemble-core`

`MicrometerBehavioralMetrics` implements the SPI against a `MeterRegistry`, following the
existing `MicrometerToolMetrics` pattern (`compileOnly` Micrometer dependency, so users who
do not want it pull nothing transitively).

| Metric | Type | Tags |
|--------|------|------|
| `agentensemble.task.iterations` | DistributionSummary | `agent_role`, `task_name` |
| `agentensemble.llm.response_type` | Counter | `agent_role`, `response_type` |
| `agentensemble.llm.tool_selection` | Counter | `agent_role`, `tool_name` |
| `agentensemble.task.novelty_ratio` | DistributionSummary | `agent_role` |
| `agentensemble.delegation.depth` | DistributionSummary | `agent_role` |
| `agentensemble.validation.outcome` | Counter | `gate`, `agent_role`, `outcome` |

Both distribution summaries are registered with `publishPercentileHistogram()`. Without it,
Micrometer exports only count, sum, and max, and the tail-fattening that makes
iteration-count the highest-value drift signal is exactly what a max cannot show. Percentile
histograms are the reason this metric is worth collecting at all.

**Common tags and version attribution.** Behavioral metrics are worthless for regression
attribution unless they carry the version of the thing that changed. Prompt edits are not
code deployments -- they do not appear in deploy markers and may not be in version control --
so without a prompt-version dimension a drift can be detected but never attributed.

The Micrometer implementation accepts common tags applied to every meter it registers:

```java
BehavioralMetrics metrics = MicrometerBehavioralMetrics.builder(registry)
        .commonTag("prompt_version", "7")
        .commonTag("model", "claude-opus-4")
        .commonTag("deployment", "prod")
        .build();
```

Users should be directed to set `prompt_version` in the guide, in the strongest terms the
documentation tone allows.

**Ensemble wiring.** A builder method mirroring the existing `toolMetrics` configuration
constructs and registers the listener, so the common case needs no knowledge of the listener
class:

```java
Ensemble.builder()
        .behavioralMetrics(metrics)   // registers BehavioralMetricsListener internally
        .build();
```

Registering `BehavioralMetricsListener` directly via `.listener(...)` remains supported for
users who want to control ordering or wrap the sink.

### 3.6 Cardinality

Every tag in the table above is bounded by configuration rather than by traffic: agent roles,
tool names, task names, response types, gate names, and decisions are all fixed at build
time. This is deliberate and must stay true.

The following must never become tags: task descriptions, prompt text, tool arguments, tool
results, user input, session or run identifiers, or call signatures. Each is unbounded, and
each new distinct value allocates a permanent time series in most registries. Signatures in
particular exist only inside the accumulator and are never passed to the SPI -- the SPI
receives the derived ratio, a single double.

Per-run detail belongs in `ExecutionTrace` and in spans, where it can be queried after the
fact without allocating a series. This division -- bounded aggregates in the metrics backend,
high-dimensional detail in the trace backend -- is the single most important operational
constraint in this design.

## 4. Issue Dependency Graph

```
BM-001 (BehavioralMetrics SPI)
   └──> BM-002 (BehavioralMetricsListener)
            ├──> BM-003 (novelty ratio + signatures)
            └──> BM-005 (Micrometer impl + wiring)

BM-004 (validation gate events) ──> BM-002 consumes them
                                    (independently valuable; ships standalone)

Design 31 / OT-001 (taskIndex on iteration + delegation events)
   └──> BM-002 keys accumulators by taskIndex
```

**BM-002 depends on OT-001 from design 31.** `LlmIterationCompletedEvent` and
`DelegationStartedEvent` do not currently carry `taskIndex`, so per-task accumulation cannot
key on them. OT-001 adds the field to both. Sequencing OT-001 first serves both designs;
absent it, BM-002 would need to key on `(agentRole, taskDescription)`, which is ambiguous
when the same role runs concurrent tasks under `PARALLEL`.

BM-004 has no dependency on the rest and can be implemented, reviewed, and merged
independently.

## 5. Testing Strategy

Micrometer's `SimpleMeterRegistry` makes assertions on emitted meters ordinary unit tests. No
new test dependency is required beyond what `agentensemble-metrics-micrometer` already
declares.

### BM-001
- `NoOpBehavioralMetrics` accepts every call without throwing, including nulls.

### BM-002
- Iteration count for a task equals the number of `LlmIterationCompletedEvent`s fired.
- Response-type counter increments once per iteration with the correct label.
- Tool selection increments once per entry in `toolRequests()`, including multiple requests
  in a single iteration.
- A requested-but-blocked tool increments selection but not `ToolMetrics` executions -- the
  regression test for the selection/execution distinction.
- **Leak tests:** after N failed tasks the accumulator map is empty; after an ensemble
  completes with an abandoned task the map is empty.
- Under `PARALLEL` with concurrent tasks sharing one agent role, accumulators do not
  cross-contaminate.

### BM-003
- `{"a":1,"b":2}` and `{"b":2,"a":1}` produce identical signatures.
- Malformed JSON arguments hash without throwing.
- Ten identical calls yield a ratio of 0.1; ten distinct calls yield 1.0.
- A task with one tool call emits no ratio datapoint.
- Exceeding the signature cap keeps totals accurate and reports a lower-bound ratio.
- Raw arguments are absent from the accumulator after recording (assert by reflection or by
  exposing a test-visible view).

### BM-004
- Each new event fires from its emission site with correct field values.
- Guardrail pass and fail both produce a datapoint, with distinct outcome labels.
- A structured output requiring two correction attempts produces two `OutputRepairEvent`s
  with `attempt` 1 and 2, the second marked succeeded.
- Existing listeners compile and run unchanged against the extended interface.

### BM-005
- Every metric in the table is registered with expected name, type, and tag keys.
- Distribution summaries expose percentile histogram buckets.
- Common tags appear on every meter.
- A registry that throws on `register` does not propagate the exception into task execution.

Coverage must satisfy the existing gates: 90% line, 75% branch.

## 6. Design Decisions

**A separate SPI rather than extending `ToolMetrics`.** `ToolMetrics` models measurements
about a tool; every method takes a `toolName`. Behavioral signals have no tool subject.
Routing them through the generic escape hatch would require synthetic tool names and untyped
metric-name strings at every call site.

**Computation in core, sink pluggable.** Accumulation, canonicalization, hashing, and ratio
arithmetic are identical regardless of backend. Putting them in the Micrometer module would
force reimplementation for every future backend and would make the logic untestable without
Micrometer on the classpath.

**Tool *selection* measured separately from tool *execution*.** They diverge precisely when
something is preventing the model from doing what it intends -- a guard, a policy, a missing
tool -- which is a class of problem invisible to execution counters. Keeping both is a small
cost for a signal that has no substitute.

**Only hashes retained, never arguments.** Bounds memory to a constant per distinct call
irrespective of payload size, and keeps an always-on instrumentation path free of user data.
The privacy property matters independently of the memory one.

**Novelty ratio suppressed below two calls.** Single-call tasks have a ratio of 1.0 by
construction. Including them shifts the distribution toward 1.0 in proportion to how many
trivial tasks the workload contains, masking thrashing in the tasks that do real work.

**Signature cap under-reports rather than over-reports.** When the cap is hit, novelty is
computed against a truncated distinct set, so a pathological run appears *more* repetitive
than it is. An instrumentation approximation should fail toward raising an alarm, not toward
silence.

**Percentile histograms are mandatory, not optional.** The value of iteration-count as a
drift signal is entirely in its tail. A metric exposing only count, sum, and max cannot show
tail fattening, which makes the collection pointless.

**Common tags exposed at the sink, not per call.** `prompt_version` and friends are constant
for the lifetime of a process. Threading them through every SPI method would add a parameter
to every signature for a value that never varies within a run.

**Validation gate events kept as a standalone issue.** The events are useful to the live
dashboard, the execution trace, and audit sinks regardless of whether metrics consume them,
and they touch three modules. Bundling them with the metrics work would make a reviewable
change unreviewable.
