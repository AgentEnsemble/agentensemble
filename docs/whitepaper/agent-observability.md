# Observability for Agentic Systems: Instrumenting Software That Fails Silently

**Abstract** -- Classical application observability rests on an assumption that agentic
systems violate: that failure is loud. Metrics, logs, and traces were designed for
deterministic software in which a fault manifests as an exception, a non-2xx status, or a
latency excursion. An AI agent's characteristic failure is none of these. It completes
successfully, within normal latency, emitting no errors, and produces work that is wrong.
Every dashboard built from classical signals reports a healthy system throughout. We argue
that agent observability requires a four-layer instrumentation model -- process,
behavioral, outcome, and economic -- and that the layers most people build first are the
least informative. We describe how AgentEnsemble implements these layers in-process, where
the framework owns the execution loop and can instrument any point within it. We then
contrast this with the structurally different problem of observing CLI coding agents such
as Claude Code and OpenAI Codex, where the loop is a vendor binary and instrumentation is
limited to emitted exhaust, injected seams, and observable effects on the world. We show
that the two regimes invert: the quality layer that is hardest in-process is nearly free
out-of-process, because a human is supplying continuous labels; while the prompt-version
dimension that is trivial in-process becomes structurally unavailable. We conclude with
reference architectures for both regimes and identify the unattended-CLI case as the
configuration where neither approach is adequate.

---

## 1. Introduction

Production monitoring has a well-established vocabulary. The "golden signals" -- latency,
traffic, errors, saturation [1] -- give operators a compact model of service health.
Distributed tracing [2] reconstructs causality across process boundaries. Structured
logging provides forensic depth. Together these three pillars are sufficient to operate
most distributed systems, and the tooling ecosystem around them -- OpenTelemetry,
Prometheus, Grafana, Tempo, Loki -- is mature and interoperable.

Applying this vocabulary to AI agents produces dashboards that are simultaneously correct
and useless.

Consider a research agent that, following a model version upgrade, stops calling its web
search tool and begins answering from parametric memory. Latency *improves*, because
network round-trips disappear. Token consumption *falls*, because retrieved context no
longer enters the prompt. The error rate is unchanged at zero: no exception is thrown,
because nothing exceptional happened. The agent is now confidently fabricating, and every
classical signal reports improvement.

This is not a contrived example; it is the modal agent regression. The generalization is
that **an agent's output correctness is statistically independent of its execution
health.** No amount of refinement to latency histograms will recover the missing signal,
because the signal was never in the latency.

The instrumentation question for agents is therefore not "how do I export metrics from my
agent" -- that is a solved plumbing problem. It is the prior question: *what is the
agent-equivalent of an exception?* This paper argues there are four such equivalents,
organizes them into layers, and examines how the layers are realized under two very
different structural regimes.

---

## 2. The Failure Taxonomy

### 2.1 Why classical signals under-fit

Classical observability instruments the *machinery*. For deterministic software this is
adequate, because the machinery and the work are tightly coupled: if the code executed
without error, the work was almost certainly done, and the remaining risk is a logic bug
that testing is supposed to catch.

Agents decouple the machinery from the work. The machinery is a loop that calls a model,
parses a response, dispatches tools, and repeats. That loop can execute flawlessly while
the model inside it makes a sequence of poor decisions. There is no exception to throw,
because "chose the wrong tool" is not an error condition -- it is a valid completion of the
control flow.

### 2.2 A taxonomy of agent failures

We identify five failure modes, only the first of which classical observability detects:

**F1 -- Mechanical failure.** A tool throws, an API times out, a rate limit is hit, a JSON
parse fails irrecoverably. Detected by conventional error instrumentation. This is the
easiest and least interesting class.

**F2 -- Behavioral drift.** The agent still succeeds, but *differently* than it used to:
more reasoning iterations, a shifted distribution over tool selection, deeper delegation
chains. Drift is the leading indicator of most quality regressions, and it precedes any
movement in error rate -- often by weeks. It is invisible to classical instrumentation
because nothing failed.

**F3 -- Non-productive iteration.** The agent expends resources without advancing:
re-invoking the same tool with equivalent arguments, re-reading files it has already read,
restating conclusions. Motion without progress. This class is unique to agents; there is no
analogue in request/response software, where a request either progresses or errors.

**F4 -- Silent quality collapse.** The output is well-formed, on-topic, delivered on time,
and wrong. No layer of process instrumentation can detect this. It requires either a
ground-truth comparison, a judge, or a human.

**F5 -- Economic failure.** The work is completed correctly but at unacceptable cost. In
conventional services, cost per request is roughly stable and roughly proportional to
traffic; capacity planning handles it. In agents, cost per unit of completed work varies by
two orders of magnitude according to model behavior, and can regress dramatically without
any other signal moving.

Modes F2 through F5 constitute the substance of agent observability. Each requires a
distinct instrumentation layer.

---

## 3. A Four-Layer Instrumentation Model

We organize agent instrumentation into four layers, each answering a different question and
detecting a different failure class.

### 3.1 Layer 1 -- Process: "Did the machinery work?"

Conventional signals applied to agent-shaped boundaries: task duration, tool call latency
and error rate, LLM API latency and failure rate, retry and rate-limit counts. Detects F1.

This layer is necessary, well-understood, and insufficient. Its main value is that it is a
prerequisite for the others: the boundaries you instrument here (task, tool call, LLM
request, delegation) become the join points for higher-layer signals.

### 3.2 Layer 2 -- Behavioral: "Did it act the way it usually acts?"

The central insight of this layer is that **the unit of health is not the request, it is
the trajectory.** In a conventional service a request is a request; its shape is fixed by
the code path. In an agent, a task encloses a variable-length reasoning loop, and the
*shape* of that loop carries the signal.

The core behavioral instruments:

- **Iterations to completion** (histogram). A healthy agent exhibits a tight distribution,
  typically two to four reasoning turns. Degradation manifests first as tail fattening.
  This is the single most valuable derived signal in agent observability, and it requires
  no ground truth, no judge, and no labels.

- **Response-type distribution** (counter over `TOOL_CALLS` vs `FINAL_ANSWER`). The ratio
  is a behavioral fingerprint of the agent-model pairing. It moves the moment a prompt or
  model version changes.

- **Tool selection distribution** (counter over tool name). Distinct from tool *success*
  rate. This measures which capability the model *chose*, and it is how the fabricating
  research agent of Section 1 is caught: its search-tool selection rate goes to zero while
  every other signal improves.

- **Novelty ratio.** Distinct tool-call signatures (name plus hashed arguments) divided by
  total tool calls, per task. A value below roughly 0.5 indicates thrashing. This is the
  primary instrument for F3, and we are aware of no widely-deployed agent platform that
  emits it by default.

- **Delegation depth and fan-out.** In multi-agent systems, recursion depth and branching
  factor. Runaway delegation is the agent analogue of a fork bomb, and it is expensive.

Behavioral instrumentation is high-value and, when the framework owns the loop, nearly
free -- every quantity above is derivable from events the executor already fires.

### 3.3 Layer 3 -- Outcome: "Was the work any good?"

This layer detects F4 and is the hardest to build, because it requires a notion of
correctness external to the agent.

There are three sources of outcome signal, in ascending order of cost:

**Validation gates as sensors.** Any system with structured output, schema validation,
guardrails, or review gates is already computing a correctness judgment on every run and
usually discarding it. Structured-output repair attempts, guardrail trips, review
rejections, and policy denials are quality telemetry available at zero marginal cost. The
rate of automatic output-repair prompts in particular is a direct measurement of the
model's instruction-following fidelity, and it is one of the earliest indicators of a model
regression. Emitting these as counters is the highest return-on-effort action available in
this layer.

**Deterministic verification.** Where the domain admits it -- code that must compile, tests
that must pass, JSON that must validate against a schema, numbers that must reconcile --
the verifier is a free and unambiguous label. Coding agents are unusually fortunate here.

**Model-based evaluation.** Sampling production runs and scoring them with a judge model or
rubric. Expensive and imperfect, but the only option for open-ended generative work. The
critical design requirement is that judge scores must be emitted **with the same label set
as every other signal** -- agent role, model, prompt version, tool set -- so that quality
can be sliced along the same dimensions as latency and cost. A quality score that cannot be
joined to a deployment dimension identifies that something is wrong without identifying
what changed.

### 3.4 Layer 4 -- Economic: "What did it cost to get there?"

Token consumption, cost, and cache utilization, attributed to model, agent, and task.

The key derived metric is **cost per completed unit of work** -- tokens or dollars per task
that reached an accepted terminal state, not per invocation. This is the agent equivalent
of saturation. Unlike CPU utilization, it is not bounded by physical capacity, which means
there is no natural backpressure and no upper limit on how badly it can regress. A prompt
change that adds one reasoning iteration to the median task raises marginal cost by 30--50%
with no other observable effect.

Cache-hit ratio on prompt prefixes deserves separate treatment, since cached input tokens
are typically an order of magnitude cheaper than uncached ones; a change that invalidates a
stable prompt prefix can multiply cost without changing token counts materially.

### 3.5 Two cross-cutting constraints

**Cardinality discipline.** Metric labels must be bounded: agent role, stable task
identifier, tool name, model, prompt version. Never task descriptions, prompt text, user
input, or session identifiers. High-dimensional per-run data belongs in a trace or event
store where it can be queried after the fact, not in a time-series database where each
distinct label combination allocates a permanent series. The most common failure in agent
observability deployments is attempting to express behavioral detail as Prometheus labels.

**Version attribution is non-negotiable.** Every metric and span must carry the triple
`(framework version, model identifier, prompt version)`. Agents change behavior when
prompts change, and prompt edits are not code deployments -- they do not appear in deploy
markers, they may not be in version control, and they may be made by someone who is not an
engineer. Without a prompt-version dimension, a behavioral regression can be *detected* but
never *attributed*. As Section 5.6 shows, this constraint is what most sharply distinguishes
the two instrumentation regimes.

---

## 4. In-Process Instrumentation: The AgentEnsemble Approach

When the framework owns the execution loop, instrumentation is a white-box problem: any
quantity that can be computed at any point in the loop can be exported. AgentEnsemble
exploits this through six mechanisms.

### 4.1 The event model

The `EnsembleListener` interface is the primary instrumentation seam. It defines callbacks
at every semantically meaningful boundary in the execution loop:

| Boundary | Events |
|---|---|
| Run | `onEnsembleStarted`, `onEnsembleCompleted` |
| Task | `onTaskStart`, `onTaskInput`, `onTaskComplete`, `onTaskFailed` |
| Reasoning iteration | `onLlmIterationStarted`, `onLlmIterationCompleted` |
| Streaming | `onToken` |
| Tool | `onToolCall` |
| Delegation | `onDelegationStarted`, `onDelegationCompleted`, `onDelegationFailed` |
| Control flow | `onLoopIterationCompleted`, `onGraphStateCompleted` |
| Quality | `onTaskReflected` |
| Side effects | `onFileChanged` |

Three properties matter for observability. First, every method has a default no-op
implementation, so an instrumentation listener declares only the boundaries it cares about.
Second, listener exceptions are caught and logged by the framework -- a broken metrics
exporter cannot abort a run, which is the correct failure semantic for instrumentation.
Third, listeners may be invoked concurrently from multiple virtual threads under parallel
workflows, so implementations must be thread-safe; this is a constraint, but it is the
price of not serializing execution behind instrumentation.

The presence of `onLlmIterationStarted` and `onLlmIterationCompleted` is what makes Layer 2
instrumentation tractable. Iteration counts, response-type distributions, and per-iteration
latency are all directly observable rather than inferred.

### 4.2 The structured trace

Alongside the event stream, every run produces an `ExecutionTrace`: a complete, serializable
record of what happened, structured as a tree that mirrors the execution hierarchy.

```
ExecutionTrace
├── ensembleId, workflow, startedAt, completedAt, totalDuration
├── traceId                      -- W3C trace ID, when OTel is active
├── inputs, agents[], errors[]
├── metrics: ExecutionMetrics
├── totalCostEstimate: CostEstimate
├── loopTraces[], graphTrace, mapReduceLevels[]
└── taskTraces[]: TaskTrace
    ├── taskDescription, agentRole, duration
    ├── prompts: TaskPrompts
    ├── finalOutput, parsedOutput
    ├── metrics: TaskMetrics
    ├── delegations[]: DelegationTrace
    └── llmInteractions[]: LlmInteraction
        ├── iterationIndex, startedAt, completedAt, latency
        ├── responseType: TOOL_CALLS | FINAL_ANSWER
        ├── responseText
        ├── messages[]: CapturedMessage      -- full conversation, tier-dependent
        └── toolCalls[]: ToolCallTrace
            ├── toolName, arguments, result, structuredOutput
            ├── parsedInput                   -- tier-dependent
            ├── startedAt, completedAt, duration
            └── outcome: ToolCallOutcome
```

This structure is the framework's most distinctive observability asset. It is not a
flattened event log that must be reassembled downstream; it is the reasoning tree itself,
retaining parent-child relationships between tasks, iterations, and tool calls, with token
counts and timing at every level. It serializes to JSON via `JsonTraceExporter` and is the
substrate for offline analysis, regression comparison, and replay.

`ExecutionMetrics` aggregates across the tree: input/output/total tokens, LLM latency, tool
execution time, memory retrieval time, prompt build time, LLM call count, tool call count,
delegation count, memory operation counts, and an aggregated cost estimate. Unknown token
counts are represented as `-1` rather than `0`, preserving the distinction between "the
provider did not report usage" and "no tokens were consumed" -- a distinction that silently
corrupts cost dashboards when collapsed.

### 4.3 Tiered capture

Full-fidelity capture of every prompt, message, and tool payload is the only way to
reproduce an agent failure, and is simultaneously too large to retain, too sensitive to
centralize, and too slow to serialize on every run. `CaptureMode` resolves this with three
tiers:

| Tier | Captured | Size |
|---|---|---|
| `OFF` (default) | Prompts, tool arguments and results, timing, token counts | Minimal |
| `STANDARD` | + full LLM message history per iteration, memory operation counts | Moderate |
| `FULL` | + automatic JSON export to `./traces/`, parsed structured tool input | Larger |

The design decision worth highlighting is the resolution order: explicit builder setting,
then the `agentensemble.captureMode` JVM system property, then the
`AGENTENSEMBLE_CAPTURE_MODE` environment variable, then `OFF`. Capture depth is therefore
adjustable **without a code change or redeploy** -- a production incident can be escalated
to full fidelity by restarting with a different environment variable. This mirrors the
dynamic log-level adjustment that mature logging frameworks provide, applied to a far
richer data stream.

`CaptureMode` is orthogonal to log verbosity and to export destination; the three compose
independently.

### 4.4 Pluggable tool metrics

The `ToolMetrics` interface decouples measurement from any specific metrics backend:

```java
void incrementSuccess(String toolName, String agentRole);
void incrementFailure(String toolName, String agentRole);
void incrementError(String toolName, String agentRole);
void recordDuration(String toolName, String agentRole, Duration duration);
void incrementCounter(String metricName, String toolName, Map<String,String> tags);
void recordValue(String metricName, String toolName, double value, Map<String,String> tags);
```

The three-way outcome split -- success, failure, error -- is deliberate and matters more
than it appears. "Failure" denotes a tool that executed correctly and returned a negative
result (a search with no hits, a validation that did not pass); "error" denotes a tool that
malfunctioned. Collapsing these, as a simple success/failure counter would, destroys the
ability to distinguish "the world did not contain what the agent sought" from "our
integration is broken" -- two conditions with entirely different remediations that a
conventional error-rate metric renders identical.

`MicrometerToolMetrics` provides the reference implementation, emitting
`agentensemble.tool.executions` (counter; tags `tool_name`, `agent_role`, `outcome`) and
`agentensemble.tool.duration` (timer; tags `tool_name`, `agent_role`), which reach
Prometheus, Datadog, or any Micrometer-supported registry with no further code. The last
two methods provide an escape hatch for domain-specific instrumentation from inside tool
implementations.

### 4.5 Distributed tracing

`OTelTracingListener` bridges the event model to OpenTelemetry, producing spans at
framework boundaries: `ensemble.run` as the root, `task.execute` per task, `tool.execute`
per tool invocation, and `network.delegate` (span kind `CLIENT`) for cross-agent handoffs.
Attributes use an `agentensemble.*` namespace to avoid collision with other
instrumentation. The listener exposes `getTraceId()`, which the framework uses to stamp
`ExecutionTrace.traceId` -- creating a join between the structured trace and the
distributed trace, so that a span in Tempo can be resolved to a complete reasoning tree.

`TraceContextPropagator` implements W3C Trace Context [2] extraction and injection, which is
what allows an ensemble run to appear as a subtree of a larger distributed trace rather than
as an orphaned root. An inbound HTTP request carrying `traceparent` produces ensemble spans
correctly parented under the calling service; an outbound cross-ensemble delegation carries
the header forward. In a network of long-lived ensembles [3], this is the difference between
a debuggable system and a set of disconnected trace fragments.

### 4.6 Adaptive audit

`AuditingListener` implements audit capture with a policy-driven level that can escalate at
runtime:

| Level | Recorded |
|---|---|
| `MINIMAL` | Delegation events only |
| `STANDARD` | + task start/complete/fail, review decisions |
| `FULL` | + tool calls |

`AuditPolicy` carries a default level plus rules that compute an effective level from the
ensemble identifier and the most recent event, and `escalate(level, duration)` raises
capture depth temporarily. Escalations are generation-counted so that a stale revert cannot
overwrite a newer escalation -- a subtle but necessary correctness property when escalations
overlap.

This is the agent-appropriate answer to a problem that tail-based sampling solves poorly.
Conventional tail sampling decides retention *after* a trace completes, on the basis of
error status or duration. Agent runs need the opposite: a decision to *increase* capture
depth mid-run, triggered by a behavioral signal -- a guardrail trip, an unusual iteration
count, a delegation to a sensitive agent -- so that the remainder of the run is recorded in
full. Escalation-on-behavior is, to our knowledge, not expressible in standard OpenTelemetry
sampling configuration.

### 4.7 Live inspection

The embedded dashboard and the `agentensemble-viz` module consume the same event stream in
real time over WebSocket, rendering the execution graph, task states, token accumulation,
tool call detail, and per-agent conversation threads as a run proceeds. This addresses a
need that has no conventional-service analogue: agent runs are long enough (minutes to
hours) that operators want to watch them, and interpretable enough that watching is
actually informative. A ten-minute run that has spent eight minutes re-reading the same
file is obvious to a human watching a live view and nearly invisible in a post-hoc metric.

### 4.8 Limitations and roadmap

Honesty about current gaps is more useful than a description of an idealized system. Three
limitations in the present implementation are worth stating explicitly.

**The span model is flatter than the trace model.** `OTelTracingListener` parents every
span to the root `ensemble.run` context rather than to the enclosing task, so tool and
delegation spans appear as siblings of the tasks that invoked them. The `ExecutionTrace`
retains the true hierarchy; the OpenTelemetry projection of it does not. This is a
consequence of virtual-thread execution under parallel workflows, where `Context.current()`
does not propagate across the thread boundary, and the correct fix is to carry each task's
`Context` explicitly rather than falling back to the root.

**Tool spans have no duration.** Because `onToolCall` fires after execution completes, tool
spans are started and immediately ended with elapsed time recorded as an attribute. They
render as zero-width in a waterfall view. Backdating the start timestamp would restore the
visual.

**There is no span per reasoning iteration.** The `onLlmIterationStarted` and
`onLlmIterationCompleted` events carry message buffer, response type, token usage, and
latency, but are not currently projected into spans. The consequence is that the ReAct loop
-- the structure an operator most wants to see -- is absent from the distributed trace even
though it is fully present in the structured trace.

A fourth item is a matter of ecosystem alignment rather than defect. The `agentensemble.*`
attribute namespace is appropriate for framework-specific concepts, but the OpenTelemetry
GenAI semantic conventions [4] have converged on `gen_ai.*` for model, operation, and token
usage. Dual-emitting `gen_ai.system`, `gen_ai.request.model`, `gen_ai.operation.name`, and
`gen_ai.usage.input_tokens`/`output_tokens` would cause AgentEnsemble runs to render
correctly in Grafana, Langfuse, Arize Phoenix, and other LLM-aware backends with no
configuration. This is a small change with disproportionate ecosystem value.

Finally, Layer 2 and Layer 3 signals identified in Section 3 -- iteration histograms,
response-type and tool-selection distributions, novelty ratio, guardrail and output-repair
counters -- are computable from existing events but are not emitted as metrics by default.
The data is present; the aggregation is not yet wired.

---

## 5. Out-of-Process Instrumentation: CLI Coding Agents

### 5.1 The structural inversion

Claude Code and OpenAI Codex present a categorically different problem. The execution loop
is a vendor-controlled binary running on a developer workstation. Nothing in Section 4 is
available: there is no listener interface, no in-process trace object, no capture mode you
control. Instrumentation is restricted to three channels:

1. **Exhaust** -- telemetry the vendor chooses to emit, plus transcript files on disk.
2. **Seams** -- points where you can inject your own code: lifecycle hooks and
   self-hosted MCP servers.
3. **World effects** -- the git history, the test suite, the CI pipeline, the review queue.

The third channel is not a consolation prize. For coding agents it carries the strongest
signal available in any regime, and it has no analogue in the in-process case.

Two framing shifts follow. The unit of analysis moves from **run** to **session**, and the
population moves from **requests** to **humans**. A CLI agent is an interactive system with
a person in the loop, and that person changes what "good" means.

### 5.2 Claude Code

Claude Code's telemetry is native OpenTelemetry, enabled by `CLAUDE_CODE_ENABLE_TELEMETRY=1`
and configured through standard `OTEL_*` environment variables [5].

**Metrics.** `claude_code.session.count`, `claude_code.token.usage`, `claude_code.cost.usage`,
`claude_code.lines_of_code.count`, `claude_code.commit.count`,
`claude_code.pull_request.count`, `claude_code.code_edit_tool.decision`, and
`claude_code.active_time.total`. Cost carries context-specific attributes including `model`,
`agent.name`, `skill.name`, `mcp_server.name`, and `effort`, permitting genuine
multi-dimensional cost attribution.

**Events**, exported as OTel logs: `user_prompt`, `assistant_response`, `tool_result` (with
`tool_name`, `success`, `duration_ms`, `error_type`), `tool_decision` (with `decision`,
`source`, `tool_source`), `api_request` (with `model`, `cost_usd`, `input_tokens`,
`output_tokens`, `cache_read_tokens`, `cache_creation_tokens`, `duration_ms`), `api_error`,
`api_refusal`, `mcp_server_connection`, and others.

**Traces**, currently beta behind `CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1`, produce
`claude_code.interaction` as a per-prompt root, with `claude_code.llm_request`,
`claude_code.tool`, `claude_code.tool.blocked_on_user`, `claude_code.tool.execution`, and
`claude_code.hook` beneath it. Subprocesses inherit `TRACEPARENT` for W3C propagation.

This span decomposition is instructive, and the framework case should adopt its shape. It
places a span at every LLM request -- precisely the gap identified in Section 4.8 -- and it
separates `tool.blocked_on_user` from `tool.execution`, so that time spent awaiting a human
permission decision does not contaminate tool latency. Conflating those two would make every
latency percentile a measurement of human reaction time.

**Content redaction is default-on.** Prompt text, assistant responses, tool details, tool
content, and raw API bodies each require explicit opt-in (`OTEL_LOG_USER_PROMPTS`,
`OTEL_LOG_ASSISTANT_RESPONSES`, `OTEL_LOG_TOOL_DETAILS`, `OTEL_LOG_TOOL_CONTENT`,
`OTEL_LOG_RAW_API_BODIES`), with content truncation governed by
`CLAUDE_CODE_OTEL_CONTENT_MAX_LENGTH`. This is the tiered-capture model of Section 4.3,
implemented as deployment policy rather than as an application setting -- and correctly
biased toward privacy, since the payload is a developer's source code.

**Two defaults are inverted relative to operator interest.**
`OTEL_METRICS_INCLUDE_SESSION_ID` defaults to *true*, attaching an unbounded-cardinality
label to every metric datapoint -- one new time series per session, permanently. On a shared
Prometheus this is the first thing to disable; session correlation belongs in logs and
traces. Conversely `OTEL_METRICS_INCLUDE_VERSION` defaults to *false*, omitting
`app.version` -- which, as Section 5.6 argues, is the closest available proxy for the
prompt-version dimension and therefore the label an operator most needs. The recommended
posture is the opposite of both defaults.

**Hooks** are the injection seam [6], and the right mental model is an APM agent's bytecode
instrumentation: you do not own the code, but you receive callbacks at defined points.
Claude Code exposes an extensive set -- `SessionStart`, `SessionEnd`, `UserPromptSubmit`,
`PreToolUse`, `PostToolUse`, `PostToolUseFailure`, `PermissionRequest`, `PermissionDenied`,
`SubagentStart`, `SubagentStop`, `Stop`, `StopFailure`, `PreCompact`, `PostCompact`,
`FileChanged`, `Notification`, and more. Each receives JSON on stdin carrying `session_id`,
`prompt_id`, `transcript_path`, `cwd`, `permission_mode`, `hook_event_name`, and where
applicable `agent_id` and `agent_type`.

Three consequences matter for instrumentation design. `PreToolUse`/`PostToolUse` reconstruct
from outside exactly what the `ToolMetrics` SPI provides from inside.
`SubagentStart`/`SubagentStop` with `agent_id` and `agent_type` permit reconstruction of the
delegation tree -- the out-of-process equivalent of `network.delegate` spans. And `prompt_id`
is the join key between the hook channel and the OTel channel; without it the two are
disjoint universes, with it hooks become a way to *enrich* vendor traces rather than shadow
them.

The binding constraint is that hooks are subprocesses on the critical path, with timeouts
ranging from 600 seconds down to 30 seconds for `UserPromptSubmit`, 10 seconds for
`MessageDisplay`, and a shared budget of roughly 1.5 seconds across all `SessionEnd` hooks.
Instrumentation hooks must therefore be strictly fire-and-forget: append a line to a local
file and return. A hook that makes a network call has converted your observability pipeline
into a latency dependency of the developer's editor.

### 5.3 OpenAI Codex

Codex offers the same two channels with a different maturity profile and different defaults.

**Telemetry** is configured in `~/.codex/config.toml` under an `[otel]` table, disabled by
default [7]. The relevant keys:

| Key | Values / default |
|---|---|
| `otel.environment` | string, default `"dev"` |
| `otel.exporter` | `none` \| `otlp-http` \| `otlp-grpc` |
| `otel.metrics_exporter` | `none` \| `statsig` \| `otlp-http` \| `otlp-grpc`, default `statsig` |
| `otel.trace_exporter` | `none` \| `otlp-http` \| `otlp-grpc` |
| `otel.log_user_prompt` | boolean, opt-in raw prompt export |

Each exporter accepts `.endpoint`, `.headers`, `.protocol` (`binary` \| `json`), and TLS
material via `.tls.ca-certificate`, `.tls.client-certificate`, `.tls.client-private-key`.
Session transcripts are governed by `history.persistence` (`save-all` \| `none`) and
`history.max_bytes`; `log_dir` defaults to `$CODEX_HOME/log`; and `notify` registers a
command that receives a JSON payload for notification events.

**Metrics** are where Codex is strongest, and where a first reading of its documentation
understates it. The inventory is broad and dimensional [13]:

| Area | Metrics |
|------|---------|
| Turn | `codex.turn.e2e_duration_ms`, `codex.turn.ttft.duration_ms` (time to first token), `codex.turn.ttfm.duration_ms`, `codex.turn.token_usage` (by `token_type`: total, input, `cached_input`, output, reasoning_output) |
| Tools | `codex.tool.call` and `.duration_ms` (tool, success), `codex.approval.requested` (tool, approved/denied/amended/session/abort) |
| Transport | `codex.api_request`, `codex.sse_event`, `codex.websocket.request`/`.event`, each with a paired duration histogram |
| MCP / Skills / Hooks | `codex.mcp.call` (+`.duration_ms`), `codex.skill.injected`, `codex.hooks.run` (+`.duration_ms`, tagged `hook_name`/`source`/`status`) |
| Memory | `codex.memory.phase1`/`phase2` with paired token-usage and end-to-end histograms |

Every metric carries `auth_mode`, `model`, and `app.version`, and many add `originator`,
`session_source`, and `conversation_id` [13]. Two entries deserve particular note.
`codex.turn.token_usage` breaks out `cached_input`, which yields prompt-cache effectiveness
directly -- the quantity that usually dominates marginal cost. And `codex.approval.requested`,
tagged approved or denied, exposes a human-judgment signal as a first-class metric -- an
outcome signal in the sense of Section 3.3, shipped as a counter rather than left to be built
by hand.

Against that inventory, the "thinner than Claude Code" reading is wrong on metrics
specifically: Codex emits roughly twice as many, with richer dimensions. Claude Code leads on
event breadth, hook coverage, and the nested span tree; Codex leads on metrics. The two are
not ordered.

Two differences from Claude Code do deserve emphasis. First, `metrics_exporter` defaults to
`statsig` rather than OTLP, so an operator who configures `otel.exporter` for logs and
assumes metrics follow will silently receive no metrics at their collector -- it must be set
explicitly. Second, the gaps that matter have shifted. An earlier documented gap, in which
`codex exec` emitted no metrics and `codex mcp-server` emitted no telemetry at all, was
closed as completed in February 2026 with a fix committed for the following release [8]; the
headless entrypoint is no longer dark, and any analysis resting on that gap is out of date.
What remains open is a different set: custom OTel *resource* attributes are unsupported, so
telemetry cannot be tagged by team, deployment tier, or cost center [14], and exported OTLP
log records carry `timeUnixNano = 0`, causing collectors to drop them while traces and
metrics from the same exporter arrive normally [15]. Operators should verify emission
empirically against the version they run rather than against any published gap list,
including this one.

**Hooks** are available and structurally similar to Claude Code's [9], configured in
`~/.codex/hooks.json`, `<repo>/.codex/hooks.json`, or inline `[[hooks.EventName]]` tables in
`config.toml`, organized as event → matcher group → handler array with pattern matching such
as `shell:*` and `mcp:*`. The event set is narrower: `SessionStart`, `SessionEnd`,
`UserPromptSubmit`, `PreToolUse`, `PostToolUse`, `PermissionRequest`, `PreCompact`,
`PostCompact`, `SubagentStart`, `SubagentStop`, and `Stop`. Handlers receive `session_id`,
`hook_event_name`, `cwd`, `model`, `transcript_path`, and `permission_mode`, with `turn_id`
on turn-scoped events, and respond with `continue`, `stopReason`, `systemMessage`, and
`suppressOutput`. Timeouts default to 600 seconds, with `SessionEnd` at 1 second and a
maximum of 3.

The practical differences for an observability build: Codex has no distinct
`PostToolUseFailure` event, so success and failure must be discriminated from the
`PostToolUse` payload rather than from event type; there is no `PermissionDenied` observer
event, making auto-denial harder to count; and the absence of a `prompt_id` equivalent means
correlation between hooks and OTel signals runs through `session_id` and `turn_id` instead.
The `model` field being present in the common payload is a genuine advantage -- it makes
per-model attribution available in the hook channel without a separate join.

### 5.4 The human as evaluator

The most important asymmetry in this entire comparison is that **Layer 3, the hardest layer
in-process, is nearly free out-of-process** -- because a human is watching, and their
behavior is a continuous stream of labels that no judge model can match for accuracy.

- **Interruption is the exception.** There is no stack trace when a coding agent goes wrong;
  there is a person pressing Escape. Interrupt rate per session is the error rate for Layer 1
  purposes, and it is a *human-validated* error rate.
- **Permission denial is rejection.** `claude_code.code_edit_tool.decision` and the
  `tool_decision` event carry an explicit human verdict on a proposed action. A rising
  denial rate for a specific tool is a behavioral regression that arrives pre-labeled.
- **Git is the verdict.** A commit is a positive label. A revert within a week is a negative
  one. A human editing the same file within minutes of the agent writing it is partial
  rejection. These labels are free, unambiguous, and already being recorded.
- **Prompt rephrasing is a comprehension failure.** A user restating the same request in
  different words is a signal the agent did not understand -- observable through
  `UserPromptSubmit` and available in no other way.

The corollary is a warning: per-run quality monitoring is *less* valuable for interactive
agents than for unattended ones, because the human already performs it in real time and
better. Effort spent building a judge pipeline for interactive coding sessions is largely
redundant. What the human cannot see -- and what instrumentation must supply -- is the
aggregate: whether the tool is making the team faster, and where it fails systematically.

### 5.5 Git as the correlation backbone

`claude_code.commit.count` and `claude_code.pull_request.count` are counters. They report how
many, never which. The question that actually determines whether a coding agent is working
-- *what fraction of agent-authored changes survived review and were not reverted?* -- cannot
be answered from a counter.

Constructing the join is straightforward and high-leverage. A `SessionEnd` hook records
`{session_id, cwd, branch, HEAD sha, end_reason}` to a durable store; a periodic job joins
that record against the forge's API for pull request state, review outcome, CI result, and
subsequent reverts. Once this exists, the chain

```
session_id → cwd → branch → commits → pull request → CI result → revert-or-survive
```

is complete, and **the git branch becomes the trace identifier.** This is the out-of-process
analogue of `ExecutionTrace.traceId`: the primary key that makes per-run signals joinable to
outcomes. It is the single highest-value component an organization must build itself, and no
vendor supplies it, because it necessarily spans the agent, the forge, and CI.

### 5.6 What is irreducibly lost

Three capabilities are structurally unavailable out-of-process, regardless of vendor
cooperation.

**Prompt version.** The vendor owns the system prompt. When it changes, agent behavior
changes and there is no version dimension to attribute the change to. `app.version` and
`model` are the available proxies, and they are correlational rather than causal -- a CLI
upgrade bundles prompt changes, model routing changes, tool definition changes, and harness
changes indivisibly. Section 3.5 identified prompt-version attribution as non-negotiable;
out-of-process it is unobtainable. This is the strongest argument for owning the loop when a
workload is production-critical.

**Internal loop structure.** Tool calls are observable; the model's deliberation over which
tool to select, the retry and repair machinery, context assembly, and compaction decisions
are largely not. Some of this surfaces through `PreCompact`/`PostCompact` and permission
events, but the mechanism remains opaque.

**Deterministic replay.** A session cannot be re-executed against a pinned model version and
prompt. Transcripts permit *inspection* of what happened but not *reproduction*, which means
the standard debugging loop of "reproduce, instrument, fix, verify" is unavailable.

---

## 6. Comparative Analysis

| Dimension | AgentEnsemble (in-process) | Claude Code / Codex (out-of-process) |
|---|---|---|
| Instrumentation model | White-box; any point in the loop | Exhaust + seams + world effects |
| Primary seam | `EnsembleListener` (in-process, typed) | Lifecycle hooks (subprocess, JSON) |
| Instrumentation cost | Object allocation, no process boundary | Subprocess spawn per hook, on critical path |
| Unit of analysis | Run | Session |
| Population | Requests / workloads | Humans |
| Layer 1 (process) | Built; task, tool, LLM, delegation | Built by vendor; generally more complete |
| Layer 2 (behavioral) | All inputs available; aggregation not yet wired | Partially reconstructable via hooks; some vendor-gated |
| Layer 3 (outcome) | Hard; requires judges or validators | Nearly free; human supplies continuous labels |
| Layer 4 (economic) | `CostEstimate`, `ExecutionMetrics` | First-class, per-model, per-session |
| Trace structure | Full reasoning tree in `ExecutionTrace` | Vendor spans; reasoning tree not exposed |
| Prompt-version attribution | Available and controlled | **Structurally unavailable** |
| Deterministic replay | Possible from captured messages | Not possible |
| Capture control | `CaptureMode`, runtime-escalatable audit policy | Vendor env vars, set at process start |
| Correlation key | W3C `traceparent`, `ExecutionTrace.traceId` | `session_id`, `prompt_id`/`turn_id`, **git branch** |
| Data volume | Production scale; statistical methods viable | Tens of sessions/day; forensic methods required |
| Data location | Your infrastructure, your data | Developer workstation, their source code |
| Failure of instrumentation | Caught and logged; run continues | Hook timeout degrades the developer's session |

Two rows deserve amplification.

**Volume changes the discipline.** An organization running fifty coding-agent sessions per
day cannot detect a distribution shift in iterations-to-completion; the statistics are not
there. The practice shifts from *monitoring* to *forensics* -- not alert rules but
investigation -- and this changes the storage architecture. Metrics into a time-series
database for cost and volume; the event stream into something supporting arbitrary
after-the-fact queries: Loki, ClickHouse, or DuckDB over the transcript files already on
disk. Building alerting rules for a population this small produces noise, not signal.

**Privacy inverts.** In-process agents run on your infrastructure over your data under your
retention policy, and full capture is a cost decision. CLI agents run on a developer's
machine and the payload is their source code and their prompts, which may contain
credentials and will certainly contain things people did not expect to be centrally logged.
Enabling `OTEL_LOG_USER_PROMPTS` or `otel.log_user_prompt` fleet-wide is a policy decision
requiring disclosure, not a configuration tweak. The correct default is metrics centrally,
content locally.

---

## 7. The Fourth Pillar: Evaluation

Conventional observability has three pillars: metrics, logs, traces. Agent observability
requires a fourth -- **scored outcomes** -- because the first three describe the machinery
and none of them describes the work.

Evaluation is conventionally treated as a pre-deployment activity: a benchmark suite run in
CI against a fixed dataset. This is necessary and insufficient. Offline evaluation measures
the system against yesterday's distribution of inputs; production drift arrives as a change
in the input distribution, a vendor-side model update, or a prompt edit, none of which the
benchmark observes.

Online evaluation -- sampling live runs and scoring them -- is the missing pillar, and its
integration requirement is specific: **evaluation output must be emitted through the same
pipeline, with the same label set, as every other signal.** A quality score living in a
separate evaluation dashboard, keyed differently from the metrics, cannot answer "did
quality drop for this agent role on this model after this change," which is the only
question that matters.

The layered model provides a natural cost gradient. Validation gates (Section 3.3) are free
and should always run. Deterministic verifiers are cheap where the domain provides them.
Judge models are expensive and should be sampled -- with the sample deliberately biased
toward runs that behavioral instrumentation has already flagged as anomalous. This is the
same escalation principle as the adaptive audit policy of Section 4.6: **let the cheap layer
decide where to spend the expensive layer.** Uniform random sampling of an agent population
wastes most of the evaluation budget on unremarkable runs.

---

## 8. Reference Architectures

### 8.1 In-process

```
AgentEnsemble
  ├── OTelTracingListener ──────────► OTLP ──► Collector ──► Tempo    (traces)
  ├── MicrometerToolMetrics ────────► registry ──────────► Prometheus (metrics)
  ├── Custom behavioral listener ───► registry ──────────► Prometheus (Layer 2)
  ├── AuditingListener ─────────────► AuditSink ─────────► Loki/DB    (audit)
  ├── JsonTraceExporter ────────────► object store ──────► offline analysis
  └── Dashboard / viz WebSocket ────► operator browser               (live)
```

The behavioral listener is the piece not yet supplied by the framework. It consumes
`onLlmIterationCompleted`, `onToolCall`, and `onTaskComplete`, and emits the Layer 2 metrics
of Section 3.2. It is on the order of a hundred lines and is the highest-value addition to
the current stack.

Retention should be tiered along the same gradient as capture: metrics at full resolution
for weeks, spans sampled with escalation-on-behavior, and full `ExecutionTrace` JSON retained
only for runs that failed, tripped a guardrail, or were sampled for evaluation.

### 8.2 Out-of-process

```
Claude Code / Codex
  ├── OTLP exporters ───────────────► Collector ──► Prometheus + Tempo + Loki
  │     (session_id OFF in metrics, app.version ON, content redacted)
  ├── Hooks (fire-and-forget) ──────► local JSONL ──► Alloy ──► Loki
  │     PreToolUse / PostToolUse / SubagentStart / SubagentStop / Stop
  ├── SessionEnd hook ──────────────► session↔branch registry ─┐
  ├── Transcripts on disk ──────────► batch ingest ──► ClickHouse (forensics)
  └── Forge + CI APIs ──────────────► outcome records ─────────┴──► joined outcome table
```

The two components an organization must build are the session-to-branch registry and the
outcome join. Everything else is configuration.

### 8.3 The hybrid: MCP as an instrumentation seam

A self-hosted MCP server [10] is white-box by construction: it is your process, so you
observe every invocation completely -- arguments, results, latency, errors -- with no vendor
cooperation required. This yields a useful hybrid: an opaque agent driving fully-instrumented
tools, structurally analogous to service-mesh sidecar telemetry, where the workload is
uninstrumented but every interaction crossing its boundary is observed.

For AgentEnsemble the natural form of this pattern is to place an ensemble behind a boundary
that a CLI agent can invoke. The entire in-process instrumentation stack of Section 4 then
applies inside that invocation, and `traceparent` propagation joins the ensemble's spans to
the CLI session's trace -- yielding a single trace spanning an opaque outer agent and a
fully-observable inner one: black-box where it must be, white-box where it matters.

A clarification on current capability is warranted. The `agentensemble-mcp` module is an MCP
*client* bridge: `McpToolFactory` and `McpServerLifecycle` adapt tools exposed by external
MCP servers into the `AgentTool` interface, so ensembles can consume the MCP ecosystem. It
does not currently expose ensembles *as* MCP servers. The inbound direction is available
today through the Ensemble Control API and the `agentensemble-web` HTTP surface, which a CLI
agent can call directly; an MCP server facade over that API would make the integration
idiomatic for CLI agents and is a natural extension. Either way, the observability property
is the same, and it derives from process ownership rather than from protocol: whoever hosts
the tool endpoint observes it completely.

---

## 9. Open Problems

**The unattended CLI case.** Running `claude -p` or `codex exec` in CI removes the human,
and with them every Layer 3 signal of Section 5.4 -- no interruptions, no permission
decisions, no immediate manual edits. What remains is a black-box agent with no evaluator,
which is strictly worse than either regime alone: framework-grade instrumentation is
required and out-of-process constraints prevent it. What compounds the difficulty is subtler
than a telemetry gap: the vendor signals that best approximate a quality judgment are the
ones that go inert without a human. `codex.approval.requested` [13] and Claude Code's
`code_edit_tool.decision` [5] both record a person's verdict, and in CI nobody is deciding,
so the metrics still report but report nothing. The instrumentation remains; the judgment it
encoded does not. Our position is that
production-critical unattended work belongs in a framework whose loop you own, and that CLI
agents in CI should be restricted to work whose output is verified deterministically -- tests
that must pass, builds that must succeed.

**Behavioral baselines without volume.** Every Layer 2 instrument presupposes a baseline
distribution to compare against, and low-volume deployments cannot establish one
statistically. Whether baselines can be transferred across agents, tasks, or organizations
is unresolved.

**Evaluating the evaluator.** Judge models drift, and a judge that drifts in the same
direction as the agent it scores produces a quality metric that is stable and wrong. Judge
calibration requires periodic human-labeled anchoring, and the sampling economics of that
anchoring are not well understood.

**Semantic conventions for multi-agent structure.** The OpenTelemetry GenAI conventions [4]
cover model invocations well. They do not yet standardize delegation, handoff, review gates,
or state-machine transitions -- the structures that distinguish multi-agent systems from
single-agent ones. Until they do, cross-framework comparison of agent traces remains
impossible.

**Cross-boundary trace continuity.** When an ensemble delegates to an ensemble in another
process [3], W3C propagation preserves the trace. When a CLI agent invokes an MCP-exposed
ensemble, continuity depends on the CLI propagating `traceparent` into the MCP transport --
which Claude Code does for subprocesses but which is not guaranteed across all transports or
all vendors.

---

## 10. Conclusion

Agent observability is not a plumbing problem. The exporters exist, the collectors are
mature, and the wire protocols are settled. The problem is that the signals worth exporting
are not the signals classical practice trained operators to collect.

Three claims summarize our position.

**First, instrument the trajectory, not the request.** The distribution of reasoning
iterations, the distribution over tool selection, and the ratio of novel to repeated actions
detect regressions weeks before any error rate moves, and they require no ground truth. They
are the cheapest high-value signals in agent observability and are almost universally
absent.

**Second, the two regimes invert, and the inversion should drive strategy.** In-process, you
control prompt versions, capture depth, and replay, but you must construct outcome
measurement yourself. Out-of-process, outcome measurement is nearly free because a human
labels every turn through interruptions, permission decisions, and git history, but
prompt-version attribution is permanently unavailable. Neither is uniformly superior. The
decision rule that follows is: **own the loop when correctness must be attributable; borrow
the loop when a human is present to supply correctness.**

**Third, let cheap layers direct expensive ones.** Full-fidelity capture, judge-model
evaluation, and long-retention traces are all too expensive to apply uniformly and too
valuable to omit. Behavioral instrumentation is cheap enough to run on every request and
informative enough to identify which runs deserve the expensive treatment. AgentEnsemble's
runtime-escalatable audit policy implements this principle for capture depth; the same
principle should govern evaluation sampling and trace retention.

The through-line is that agents fail silently, and silence is not the absence of signal --
it is signal in a dimension classical instrumentation does not measure. Building the
instruments for those dimensions is the work.

---

## References

[1] B. Beyer, C. Jones, J. Petoff, N. R. Murphy, *Site Reliability Engineering*, O'Reilly,
2016. Chapter 6, "Monitoring Distributed Systems" (the four golden signals).

[2] W3C, *Trace Context*, W3C Recommendation. https://www.w3.org/TR/trace-context/

[3] AgentEnsemble, *Ensemble Network: A Distributed Architecture for Autonomous Multi-Agent
Systems*. `docs/whitepaper/ensemble-network-architecture.md`

[4] OpenTelemetry, *Semantic Conventions for Generative AI Systems*.
https://opentelemetry.io/docs/specs/semconv/gen-ai/

[5] Anthropic, *Claude Code: Monitoring usage*. https://code.claude.com/docs/en/monitoring-usage

[6] Anthropic, *Claude Code: Hooks*. https://code.claude.com/docs/en/hooks

[7] OpenAI, *Codex configuration reference*.
https://developers.openai.com/codex/config-reference

[8] OpenAI, *codex exec emits no OTel metrics; codex mcp-server emits no OTel telemetry at
all*, Issue #12913. Closed as completed 2026-02-28.
https://github.com/openai/codex/issues/12913

[9] OpenAI, *Codex: Hooks*. https://developers.openai.com/codex/hooks

[10] Anthropic, *Model Context Protocol*. https://modelcontextprotocol.io/

[11] AgentEnsemble, *Metrics and Observability guide*. `docs/guides/metrics.md`

[12] AgentEnsemble, *CaptureMode guide*. `docs/guides/capture-mode.md`

[13] OpenAI, *Codex advanced configuration* (OTel metric and event reference).
https://developers.openai.com/codex/config-advanced

[14] OpenAI, *Support custom OTEL resource attributes*, Issue #30987. Open as of 2026-08-05.
https://github.com/openai/codex/issues/30987

[15] OpenAI, *otel: exported OTLP logs have timeUnixNano=0, causing collectors to drop them*,
Issue #30936. Open as of 2026-08-05. https://github.com/openai/codex/issues/30936
