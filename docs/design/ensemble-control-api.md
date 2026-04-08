# Design Doc: Ensemble Control API

**Status:** Draft
**Author:** Claude / Matt
**Module:** `agentensemble-web`
**Related:** v3 Network features (NetworkTask, NetworkTool, federation)

---

## 1. Motivation

Today the `agentensemble-web` module serves two roles:

1. **Dashboard streaming** — The `WebSocketServer` broadcasts execution events (task lifecycle, tool calls, tokens, LLM iterations, metrics, file changes, delegations) to the viz UI over `ws://host:port/ws`.
2. **Review gates** — The browser sends `ReviewDecisionMessage` to approve/edit/reject task outputs; the server blocks on a `CompletableFuture` until a decision arrives.

There is also nascent support for cross-ensemble work (`TaskRequestMessage`/`ToolRequestMessage`), directives (`DirectiveMessage`), and capability queries (`CapabilityQueryMessage`) — but these are wired for ensemble-to-ensemble communication via the v3 `NetworkClient`, not for external human/system interaction.

**What's missing:**

- No way for an external system (CI pipeline, orchestrator, CLI, custom UI) to **kick off a run** via HTTP or WebSocket.
- No way to **pass runtime parameters** beyond template variables baked into the ensemble builder.
- No way to **dynamically define tasks** — you must compile Java code to change what tasks run.
- No way to **control a running ensemble** (cancel, switch model) from outside the JVM.
- No way to **query state** (what's running, what ran, what's available) without a WebSocket connection.
- No REST alternative for **review decisions** — you need a WebSocket client.
- No **SSE** option for clients that want real-time events without WebSocket complexity.

This design doc proposes an **Ensemble Control API** that fills these gaps over both REST and WebSocket transports.

---

## 2. Current State

### 2.1 Existing REST Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/status` | Server status (running, client count, port, lifecycle state) |
| `GET` | `/api/health/live` | Liveness probe (always 200) |
| `GET` | `/api/health/ready` | Readiness probe (200 if READY, 503 otherwise) |
| `POST` | `/api/lifecycle/drain` | Trigger graceful shutdown (localhost only) |
| `GET` | `/api/workspace/files` | Directory listing (localhost only) |
| `GET` | `/api/workspace/file` | File content (localhost only, max 1MB) |

### 2.2 Existing WebSocket Protocol

**Server → Client (37 message types):**
- Lifecycle: `HelloMessage`, `EnsembleStartedMessage`, `EnsembleCompletedMessage`
- Task execution: `TaskStartedMessage`, `TaskInputMessage`, `TaskCompletedMessage`, `TaskFailedMessage`
- Tool calls: `ToolCalledMessage`
- Delegation: `DelegationStartedMessage`, `DelegationCompletedMessage`, `DelegationFailedMessage`
- Review: `ReviewRequestedMessage`, `ReviewTimedOutMessage`
- LLM iteration: `LlmIterationStartedMessage`, `LlmIterationCompletedMessage`
- Streaming: `TokenMessage`, `MetricsSnapshotMessage`, `FileChangedMessage`
- Keepalive: `HeartbeatMessage`, `PongMessage`
- Network: `TaskAcceptedMessage`, `TaskResponseMessage`, `ToolResponseMessage`
- Directives: `DirectiveAckMessage`, `DirectiveActiveMessage`
- Capacity: `CapacityUpdateMessage`, `ProfileAppliedMessage`, `CapabilityResponseMessage`

**Client → Server (5 message types):**
- `ReviewDecisionMessage` (reviewId, decision, revisedOutput)
- `PingMessage`
- `TaskRequestMessage` (cross-ensemble task delegation)
- `ToolRequestMessage` (cross-ensemble tool invocation)
- `DirectiveMessage` (human guidance injection)
- `CapabilityQueryMessage`

### 2.3 Ensemble Execution Model

```java
// Static factory — zero ceremony
EnsembleOutput result = Ensemble.run(model, Task.of("Research AI trends"));

// Builder — full control
EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .webDashboard(dashboard)
    .task(Task.builder()
        .description("Research {topic} in {year}")
        .tools(webSearchTool)
        .outputType(Report.class)
        .build())
    .workflow(Workflow.SEQUENTIAL)
    .input("topic", "AI safety")
    .build()
    .run();

// Long-running mode — shared tasks/tools, scheduler, network
ensemble.start(7329);
```

Key points:
- `ensemble.run()` is **synchronous** — blocks the calling thread until completion.
- `ensemble.run(Map<String, String> inputs)` resolves `{variable}` placeholders in task descriptions.
- `ensemble.run(RunOptions)` applies per-run overrides (maxToolOutputLength, toolLogTruncateLength).
- Task descriptions support `{variable}` template syntax resolved at run time via `TemplateResolver`.
- `Task.builder()` supports: description, expectedOutput, agent, chatLanguageModel, tools, maxIterations, outputType, context (dependencies), guardrails, review gates, memory scopes, handler (deterministic), rateLimit, reflection.
- Tasks are **immutable** (`@Value`) with `toBuilder()` for copies.
- Ensemble has `switchToFallbackModel()` / `switchToPrimaryModel()` for runtime model switching.

### 2.4 v3 Network Module (How This Complements It)

The `agentensemble-network` module handles **ensemble-to-ensemble** communication:

| Component | Purpose |
|-----------|---------|
| `NetworkTask` | Delegate a full task to a remote ensemble (30min default timeout) |
| `NetworkTool` | Invoke a single tool on a remote ensemble (30s default timeout) |
| `NetworkClient` | WebSocket client connecting to a remote ensemble's `/ws` endpoint |
| `NetworkClientRegistry` | Manages connections to multiple remote ensembles |
| `FederationRegistry` | Multi-realm ensemble federation |
| `CapacityAdvertiser` | Broadcasts capacity status to peers |
| `SharedMemory` | Cross-ensemble shared state with consistency levels |

**Key distinction:** The network module is for **peer ensembles talking to each other** (service mesh). The Control API proposed here is for **humans, UIs, CI systems, and orchestrators talking to an ensemble** (control plane). They share wire format where possible but serve different audiences.

| Concern | v3 Network (ensemble↔ensemble) | Control API (external→ensemble) |
|---------|-------------------------------|--------------------------------|
| Run a task | `NetworkTask` / `TaskRequestMessage` | `POST /api/runs` / `run_request` WS |
| Invoke a tool | `NetworkTool` / `ToolRequestMessage` | `POST /api/tools/{name}/invoke` |
| Discover capabilities | `CapabilityQueryMessage` (WS) | `GET /api/capabilities` (HTTP) |
| Federation/capacity | `FederationRegistry`, `CapacityAdvertiser` | Not duplicated |
| Event streaming | N/A (peers don't need viz) | WebSocket + SSE |
| Review gates | `ReviewDecisionMessage` (WS) | Same + `POST /api/reviews/{id}` |
| Directives | `DirectiveMessage` (WS) | Same + `POST /api/runs/{id}/inject` |

---

## 3. Design Principles

1. **REST-first for request/response, WebSocket for streaming.** Run submission, state queries, and control commands are natural REST calls. Real-time events stay on WebSocket. Both transports get the same logical operations where it makes sense.

2. **Complement, don't duplicate, v3 networking.** The network module handles inter-ensemble work. This API handles external-to-ensemble interaction. They share message types where possible.

3. **Same `/ws` endpoint.** New WebSocket messages extend the existing `ClientMessage`/`ServerMessage` sealed interfaces. No new WebSocket endpoints.

4. **Backwards compatible.** All existing viz/review protocol messages unchanged. New messages are additive. Existing clients that don't send new message types continue working identically.

5. **Catalog-driven.** Tools and models are referenced by **name** in API requests and resolved from server-side catalogs. This keeps the API transport-agnostic (no Java class references in JSON) and allows the server to control what's available.

---

## 4. Detailed API Specification

### 4.1 Run Submission

**Purpose:** Submit a new ensemble run with full parameterization — from simple template variables to dynamically-defined tasks with model/tool/workflow overrides.

Three levels of parameterization, each a superset of the previous:

#### Level 1: Template Inputs

Run the pre-configured ensemble tasks with variable substitution.

```
POST /api/runs
Content-Type: application/json
```

```json
{
  "inputs": {
    "topic": "AI safety",
    "year": "2025"
  },
  "tags": {
    "triggeredBy": "ci-pipeline",
    "environment": "staging"
  }
}
```

**Behavior:** Calls `ensemble.run(inputs)` on the template ensemble. `{topic}` and `{year}` in task descriptions and expected outputs are resolved. Tags are metadata attached to the `RunState` for filtering/querying.

**Response:**
```json
HTTP 202 Accepted
{
  "runId": "run-7f3a2b",
  "status": "ACCEPTED",
  "tasks": 3,
  "workflow": "SEQUENTIAL"
}
```

#### Level 2: Per-Task Overrides

Target specific tasks and override their configuration at runtime.

```json
{
  "inputs": { "topic": "AI safety" },
  "taskOverrides": {
    "researcher": {
      "description": "Research {topic} focusing specifically on EU regulation",
      "expectedOutput": "A regulatory analysis report with citations",
      "model": "opus",
      "maxIterations": 15,
      "additionalContext": "The EU AI Act was formally adopted in March 2024. Focus on Article 6 high-risk classifications.",
      "tools": {
        "add": ["web_search", "file_read"],
        "remove": ["calculator"]
      }
    },
    "writer": {
      "model": "sonnet",
      "maxIterations": 5
    }
  },
  "options": {
    "maxToolOutputLength": 5000,
    "verbose": true
  }
}
```

**Task matching:** Tasks are matched by a `name` field (new optional field on `Task.builder()`) or by prefix-matching against the task description. If no match is found, the override is rejected with a 400 error listing available task names.

**Override semantics:**
- `description` / `expectedOutput`: Replaces the task's value (still supports `{variable}` templates).
- `model`: Resolves from `ModelCatalog` by alias. Replaces the task's `chatLanguageModel`.
- `maxIterations`: Replaces the task's max iteration count.
- `additionalContext`: Injected as extra context into the agent prompt (appended to the task's assembled context, not replacing existing context dependencies).
- `tools.add` / `tools.remove`: Resolved from `ToolCatalog` by name. Added to or removed from the task's tool list.

**Implementation:** Uses `Task.toBuilder()` to create a modified copy. The original task list is never mutated.

#### Level 3: Dynamic Task Creation

Define entirely new tasks at runtime — no pre-configured ensemble tasks needed.

```json
{
  "tasks": [
    {
      "name": "researcher",
      "description": "Research the competitive landscape for {product}",
      "expectedOutput": "A competitive analysis identifying 5 key competitors with strengths and weaknesses",
      "tools": ["web_search", "calculator"],
      "model": "sonnet",
      "maxIterations": 20,
      "outputSchema": {
        "type": "object",
        "properties": {
          "competitors": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "strengths": { "type": "array", "items": { "type": "string" } },
                "weaknesses": { "type": "array", "items": { "type": "string" } }
              }
            }
          }
        }
      }
    },
    {
      "name": "writer",
      "description": "Write an executive brief based on the research",
      "expectedOutput": "A 1-page executive summary suitable for C-suite",
      "context": ["$researcher"],
      "model": "sonnet"
    }
  ],
  "inputs": { "product": "AgentEnsemble" },
  "options": {
    "workflow": "SEQUENTIAL",
    "reviewPolicy": "AFTER_LAST_TASK"
  }
}
```

**Task definition fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | No | Identifier for context references and overrides. Auto-generated from description if omitted. |
| `description` | string | Yes | What the agent should do. Supports `{variable}` templates. |
| `expectedOutput` | string | No | Expected output format/content. Defaults to `Task.DEFAULT_EXPECTED_OUTPUT`. |
| `tools` | string[] | No | Tool names resolved from `ToolCatalog`. |
| `model` | string | No | Model alias resolved from `ModelCatalog`. Falls back to ensemble default. |
| `maxIterations` | int | No | Max ReAct loop iterations. Default: 25. |
| `context` | string[] | No | Dependencies: `$name` references another task by name, `$0`/`$1` by index. |
| `outputSchema` | object | No | JSON Schema for structured output. Generates a runtime `outputType`. |
| `review` | string | No | `"required"`, `"skip"`, or omit for ensemble default. |
| `additionalContext` | string | No | Extra text injected into the agent prompt. |

**Context DAG:** When `context` references are present, the workflow is automatically inferred as `PARALLEL` (DAG-based) unless explicitly overridden. Tasks with no unmet dependencies start immediately.

**outputSchema → outputType:** When `outputSchema` is a JSON Schema object, `RunRequestParser` generates a runtime type adapter that validates the LLM's JSON output against the schema. This uses the existing `StructuredOutputParser` infrastructure with a schema-driven (rather than class-driven) approach.

#### Run Options (all levels)

```json
"options": {
  "maxToolOutputLength": 5000,
  "toolLogTruncateLength": -1,
  "workflow": "SEQUENTIAL",
  "verbose": true,
  "reviewPolicy": "AFTER_EVERY_TASK",
  "captureMode": "DETAILED"
}
```

| Field | Type | Default | Maps to |
|-------|------|---------|---------|
| `maxToolOutputLength` | int | ensemble default | `RunOptions.maxToolOutputLength` |
| `toolLogTruncateLength` | int | ensemble default | `RunOptions.toolLogTruncateLength` |
| `workflow` | string | inferred | `Workflow` enum |
| `verbose` | boolean | false | `Ensemble.verbose` |
| `reviewPolicy` | string | ensemble default | `ReviewPolicy` enum |
| `captureMode` | string | ensemble default | `CaptureMode` enum |

#### WebSocket Equivalents

```json
// Client → Server
{
  "type": "run_request",
  "requestId": "req-1",
  "inputs": { "topic": "AI safety" },
  "tasks": [...],
  "taskOverrides": {...},
  "options": {...},
  "tags": {...}
}

// Server → Client (immediate)
{
  "type": "run_ack",
  "requestId": "req-1",
  "runId": "run-7f3a2b",
  "status": "ACCEPTED",
  "tasks": 3,
  "workflow": "SEQUENTIAL"
}

// Server → Client (on completion, targeted to originating session)
{
  "type": "run_result",
  "runId": "run-7f3a2b",
  "status": "COMPLETED",
  "outputs": [
    { "taskName": "researcher", "output": "...", "durationMs": 8200 },
    { "taskName": "writer", "output": "...", "durationMs": 3100 }
  ],
  "durationMs": 11340,
  "metrics": {
    "totalTokens": 15230,
    "totalToolCalls": 7,
    "costEstimate": { "inputCost": 0.045, "outputCost": 0.012 }
  }
}
```

The `run_result` is sent **only to the session that submitted the run**. The existing `EnsembleCompletedMessage` continues to broadcast to **all** sessions (backwards compatible — the viz still works).

#### Error Responses

```json
// Unknown tool name
HTTP 400
{ "error": "INVALID_TOOL", "message": "Unknown tool 'foobar'. Available: [web_search, calculator, file_read]" }

// Unknown model alias
HTTP 400
{ "error": "INVALID_MODEL", "message": "Unknown model 'gpt-4'. Available: [sonnet, opus, haiku]" }

// Unknown task name in override
HTTP 400
{ "error": "INVALID_TASK_OVERRIDE", "message": "No task matching 'foobar'. Available: [researcher, writer]" }

// Circular context dependency
HTTP 400
{ "error": "CIRCULAR_DEPENDENCY", "message": "Tasks form a cycle: researcher → writer → researcher" }

// Ensemble not configured for API runs (no template ensemble or catalogs)
HTTP 503
{ "error": "NOT_CONFIGURED", "message": "Ensemble Control API not configured. Set toolCatalog/modelCatalog on WebDashboard.builder()." }

// Concurrency limit reached
HTTP 429
{ "error": "CONCURRENCY_LIMIT", "message": "Maximum concurrent runs (5) reached. Retry later.", "retryAfterMs": 5000 }
```

---

### 4.2 Run Control

**Purpose:** Cancel or reconfigure a running ensemble mid-flight.

#### Cancel a Run

```
POST /api/runs/{runId}/cancel
```

```json
// Response
HTTP 200
{ "runId": "run-7f3a2b", "status": "CANCELLING" }

// If run already completed
HTTP 409
{ "error": "RUN_COMPLETED", "message": "Run run-7f3a2b already completed with status COMPLETED" }

// If run not found
HTTP 404
{ "error": "RUN_NOT_FOUND", "message": "No run with ID run-7f3a2b" }
```

**Implementation:** Cooperative cancellation. `RunManager` sets a `cancelled` flag on the `RunState`. A `RunManager`-installed `EnsembleListener.onTaskStart()` checks this flag before each task and throws `ExitEarlyException` if set. Running tasks complete normally (no thread interruption) — cancellation takes effect at the next task boundary.

The `EnsembleCompletedMessage` will report `exitReason: "CANCELLED"`.

#### Switch Model Mid-Run

```
POST /api/runs/{runId}/model
Content-Type: application/json
```

```json
{ "model": "haiku" }
```

```json
// Response
HTTP 200
{ "runId": "run-7f3a2b", "model": "haiku", "previousModel": "sonnet", "status": "APPLIED" }
```

**Implementation:** Resolves the model alias from `ModelCatalog`, then calls a new `ensemble.switchToModel(ChatModel)` method (extends the existing `switchToFallbackModel()`/`switchToPrimaryModel()` pair). Takes effect on the next LLM call — the current in-flight LLM call (if any) completes with the previous model.

#### WebSocket Equivalents

```json
// Cancel
{ "type": "run_control", "runId": "run-7f3a2b", "action": "cancel" }
→ { "type": "run_control_ack", "runId": "run-7f3a2b", "action": "cancel", "status": "CANCELLING" }

// Switch model
{ "type": "run_control", "runId": "run-7f3a2b", "action": "switch_model", "model": "haiku" }
→ { "type": "run_control_ack", "runId": "run-7f3a2b", "action": "switch_model", "status": "APPLIED", "model": "haiku" }
```

---

### 4.3 State Query

**Purpose:** Query execution state, run history, and available capabilities.

#### List Runs

```
GET /api/runs
GET /api/runs?status=RUNNING
GET /api/runs?tag=triggeredBy:ci-pipeline
GET /api/runs?limit=10&offset=0
```

```json
HTTP 200
{
  "runs": [
    {
      "runId": "run-7f3a2b",
      "status": "RUNNING",
      "startedAt": "2025-03-15T10:30:00Z",
      "durationMs": null,
      "taskCount": 3,
      "completedTasks": 1,
      "workflow": "SEQUENTIAL",
      "tags": { "triggeredBy": "ci-pipeline" },
      "currentTask": { "name": "writer", "index": 1 }
    },
    {
      "runId": "run-2e8f1c",
      "status": "COMPLETED",
      "startedAt": "2025-03-15T10:25:00Z",
      "durationMs": 11340,
      "taskCount": 2,
      "completedTasks": 2,
      "workflow": "SEQUENTIAL",
      "tags": {}
    }
  ],
  "total": 2
}
```

#### Get Run Detail

```
GET /api/runs/{runId}
```

```json
HTTP 200
{
  "runId": "run-7f3a2b",
  "status": "COMPLETED",
  "startedAt": "2025-03-15T10:30:00Z",
  "completedAt": "2025-03-15T10:30:11Z",
  "durationMs": 11340,
  "workflow": "SEQUENTIAL",
  "inputs": { "topic": "AI safety" },
  "tags": { "triggeredBy": "ci-pipeline" },
  "tasks": [
    {
      "name": "researcher",
      "description": "Research AI safety focusing on EU regulation",
      "status": "COMPLETED",
      "agentRole": "Regulatory Researcher",
      "durationMs": 8200,
      "tokenCount": 10150,
      "toolCallCount": 5,
      "output": "## EU AI Act Analysis\n..."
    },
    {
      "name": "writer",
      "description": "Write an executive brief",
      "status": "COMPLETED",
      "agentRole": "Technical Writer",
      "durationMs": 3100,
      "tokenCount": 5080,
      "toolCallCount": 2,
      "output": "# Executive Brief\n..."
    }
  ],
  "metrics": {
    "totalTokens": 15230,
    "totalToolCalls": 7,
    "costEstimate": { "inputCost": 0.045, "outputCost": 0.012 }
  },
  "pendingReviews": []
}
```

#### List Capabilities

```
GET /api/capabilities
```

```json
HTTP 200
{
  "tools": [
    { "name": "web_search", "description": "Search the web using Google" },
    { "name": "calculator", "description": "Evaluate mathematical expressions" },
    { "name": "file_read", "description": "Read a file from the workspace" }
  ],
  "models": [
    { "alias": "sonnet", "provider": "anthropic" },
    { "alias": "opus", "provider": "anthropic" },
    { "alias": "haiku", "provider": "anthropic" }
  ],
  "sharedTasks": [
    { "name": "research", "description": "Research a topic using web search" }
  ],
  "sharedTools": [
    { "name": "code_search", "description": "Search codebase" }
  ],
  "preconfiguredTasks": [
    { "name": "researcher", "description": "Research {topic} in {year}", "tools": ["web_search"], "variables": ["topic", "year"] },
    { "name": "writer", "description": "Write a report based on research", "tools": [], "variables": [] }
  ]
}
```

This serves the same data as `CapabilityQueryMessage`/`CapabilityResponseMessage` in the v3 network protocol, but over HTTP — accessible to service meshes, monitoring tools, and UIs without a WebSocket connection.

#### WebSocket Equivalent

```json
{ "type": "state_query", "requestId": "q-1", "query": "runs" }
→ { "type": "state_response", "requestId": "q-1", "data": { "runs": [...] } }

{ "type": "state_query", "requestId": "q-2", "query": "run_detail", "runId": "run-7f3a2b" }
→ { "type": "state_response", "requestId": "q-2", "data": { "runId": "run-7f3a2b", ... } }

{ "type": "state_query", "requestId": "q-3", "query": "capabilities" }
→ { "type": "state_response", "requestId": "q-3", "data": { "tools": [...], "models": [...] } }
```

---

### 4.4 Context Injection

**Purpose:** Push additional context or guidance into a running ensemble.

```
POST /api/runs/{runId}/inject
Content-Type: application/json
```

```json
{
  "target": "researcher",
  "content": "Focus on European regulations, specifically the EU AI Act Article 6 high-risk classifications",
  "priority": "HIGH"
}
```

```json
HTTP 200
{ "directiveId": "dir-abc123", "status": "ACTIVE" }
```

**Implementation:** Creates a `Directive` and adds it to the ensemble's `DirectiveStore`. The existing `DirectiveDispatcher` infrastructure picks it up and injects it into the target agent's prompt on the next LLM iteration.

**WebSocket:** Already supported via `DirectiveMessage`. This REST endpoint is a thin wrapper.

---

### 4.5 Event Subscription Filtering (WebSocket only)

**Purpose:** Allow clients to subscribe to specific event types and/or specific runs instead of receiving the full firehose.

```json
// Subscribe to only task-level events for a specific run
{
  "type": "subscribe",
  "events": ["task_started", "task_completed", "task_failed", "run_result"],
  "runId": "run-7f3a2b"
}
→ { "type": "subscribe_ack", "events": ["task_started", "task_completed", "task_failed", "run_result"], "runId": "run-7f3a2b" }

// Subscribe to everything (reset to default)
{ "type": "subscribe", "events": ["*"] }
→ { "type": "subscribe_ack", "events": ["*"] }
```

**Default behavior (no subscribe message sent):** All events, all runs — identical to current behavior. Fully backwards compatible.

**Implementation:** `SubscriptionManager` maintains a per-session filter set. `ConnectionManager.broadcast()` checks the filter before sending. Messages that don't match are silently dropped for that session.

**Subscribable event types:** `ensemble_started`, `ensemble_completed`, `task_started`, `task_input`, `task_completed`, `task_failed`, `tool_called`, `token`, `llm_iteration_started`, `llm_iteration_completed`, `delegation_started`, `delegation_completed`, `delegation_failed`, `review_requested`, `review_timed_out`, `file_changed`, `metrics_snapshot`, `run_result`, `run_control_ack`.

---

### 4.6 SSE Event Stream (REST only)

**Purpose:** Real-time event streaming for clients that can't or don't want to use WebSocket.

```
GET /api/runs/{runId}/events
Accept: text/event-stream
```

```
event: task_started
data: {"type":"task_started","taskIndex":0,"taskDescription":"Research AI safety","agentRole":"Researcher","startedAt":"2025-03-15T10:30:00Z"}

event: tool_called
data: {"type":"tool_called","toolName":"web_search","durationMs":1200,"outcome":"SUCCESS"}

event: task_completed
data: {"type":"task_completed","taskIndex":0,"durationMs":8200,"tokenCount":10150}

event: run_result
data: {"type":"run_result","runId":"run-7f3a2b","status":"COMPLETED","durationMs":11340}
```

**Implementation:** Uses Javalin's `ctx.future()` with `SseClient`. The `SseHandler` registers as an `EnsembleListener` scoped to the target run and writes events as SSE frames. Connection closes when the run completes or the client disconnects.

**Query parameters:**
- `events=task_started,task_completed` — filter to specific event types (same as WS subscribe)
- `from=0` — replay from event index N (for reconnection)

---

### 4.7 REST Review Decisions

**Purpose:** Submit review decisions via REST instead of WebSocket.

```
POST /api/reviews/{reviewId}
Content-Type: application/json
```

```json
{ "decision": "CONTINUE" }
// or
{ "decision": "EDIT", "revisedOutput": "Updated output text..." }
// or
{ "decision": "EXIT_EARLY" }
```

```json
HTTP 200
{ "reviewId": "rev-abc123", "decision": "CONTINUE", "status": "APPLIED" }

// If review already decided or timed out
HTTP 409
{ "error": "REVIEW_RESOLVED", "message": "Review rev-abc123 already resolved" }
```

**Implementation:** Delegates to `ConnectionManager.resolveReview()` — same path as `ReviewDecisionMessage` from WebSocket.

---

### 4.8 Direct Tool Invocation

**Purpose:** Invoke a registered tool directly without running a full ensemble.

```
POST /api/tools/{name}/invoke
Content-Type: application/json
```

```json
{ "input": "What is 42 * 17?" }
```

```json
HTTP 200
{
  "tool": "calculator",
  "status": "SUCCESS",
  "output": "714",
  "durationMs": 2
}
```

**Implementation:** Resolves tool from `ToolCatalog`, calls `tool.execute(input)`, returns result. Reuses the same pattern as `EnsembleRequestHandler.handleToolRequest()` but sourcing from the catalog instead of the shared tool registry.

---

## 5. Supporting Infrastructure

### 5.1 ToolCatalog

Registry mapping tool names to `AgentTool` instances. Configured at dashboard build time.

```java
public class ToolCatalog {
    private final Map<String, AgentTool> tools;  // unmodifiable

    public static Builder builder() { ... }

    public AgentTool resolve(String name);           // throws if not found
    public Optional<AgentTool> find(String name);    // returns empty if not found
    public List<ToolInfo> list();                     // name + description for each tool
    public boolean contains(String name);

    public static class ToolInfo {
        String name;
        String description;
    }

    public static class Builder {
        public Builder tool(String name, AgentTool tool);
        public Builder tool(String name, Object annotatedToolObject);  // @Tool methods
        public ToolCatalog build();
    }
}
```

### 5.2 ModelCatalog

Registry mapping model aliases to `ChatModel` instances.

```java
public class ModelCatalog {
    private final Map<String, ChatModel> models;  // unmodifiable
    private final Map<String, StreamingChatModel> streamingModels;  // optional

    public static Builder builder() { ... }

    public ChatModel resolve(String alias);
    public Optional<ChatModel> find(String alias);
    public StreamingChatModel resolveStreaming(String alias);  // may return null
    public List<ModelInfo> list();

    public static class ModelInfo {
        String alias;
        String provider;  // derived from model class name
    }

    public static class Builder {
        public Builder model(String alias, ChatModel model);
        public Builder model(String alias, ChatModel model, StreamingChatModel streaming);
        public ModelCatalog build();
    }
}
```

### 5.3 RunManager

Coordinates run lifecycle: submission, tracking, concurrency control, completion notification.

```java
public class RunManager {
    private final ConcurrentHashMap<String, RunState> runs;
    private final Semaphore concurrencyLimit;  // configurable max concurrent runs
    private final ExecutorService executor;     // virtual-thread-per-task
    private final Ensemble templateEnsemble;    // the pre-configured ensemble (for Level 1+2)
    private final ToolCatalog toolCatalog;
    private final ModelCatalog modelCatalog;
    private final RunRequestParser parser;
    private final ConnectionManager connectionManager;
    private final int maxRetainedRuns;          // evict oldest completed runs

    // Submit a run (async — returns immediately)
    public RunState submitRun(RunRequest request, String originSessionId);

    // Cancel a run
    public boolean cancelRun(String runId);

    // Switch model mid-run
    public boolean switchModel(String runId, String modelAlias);

    // Query state
    public List<RunState> listRuns(RunFilter filter);
    public Optional<RunState> getRun(String runId);
}
```

**Concurrency:** `submitRun()` tries `concurrencyLimit.tryAcquire()`. If full, returns a `RunState` with status `REJECTED` and the REST layer returns 429. On run completion (success or failure), the permit is released.

**Execution:** Spawns a virtual thread that:
1. Builds the `Ensemble` via `RunRequestParser` (resolves tools, models, task overrides, context DAG)
2. Installs a cancellation-checking `EnsembleListener`
3. Calls `ensemble.run(inputs, runOptions)` (blocking in the virtual thread)
4. On completion, updates `RunState`, sends `RunResultMessage` to the originator session
5. Releases the concurrency permit

### 5.4 RunState

Immutable snapshots with a mutable status field (via AtomicReference).

```java
public class RunState {
    String runId;
    AtomicReference<Status> status;  // ACCEPTED, RUNNING, COMPLETED, FAILED, CANCELLED, REJECTED
    Instant startedAt;
    Instant completedAt;
    Map<String, String> inputs;
    Map<String, Object> tags;
    int taskCount;
    AtomicInteger completedTasks;
    String workflow;
    String originSessionId;
    List<TaskOutputSnapshot> taskOutputs;  // populated as tasks complete
    ExecutionMetrics metrics;
    Ensemble ensemble;  // live reference for control operations
    volatile boolean cancelled;
}
```

### 5.5 RunRequestParser

Converts JSON request bodies into `Ensemble.builder()` calls.

```java
public class RunRequestParser {
    private final ToolCatalog toolCatalog;
    private final ModelCatalog modelCatalog;

    // Level 1: just inputs
    public Ensemble buildFromTemplate(Ensemble template, Map<String, String> inputs, RunOptions options);

    // Level 2: template + task overrides
    public Ensemble buildWithOverrides(Ensemble template, Map<String, String> inputs,
                                        Map<String, TaskOverride> overrides, RunOptions options);

    // Level 3: fully dynamic tasks
    public Ensemble buildDynamic(List<TaskDefinition> taskDefs, Map<String, String> inputs,
                                  RunOptions options, ChatModel defaultModel);
}
```

**Level 3 context DAG resolution:**
1. Parse `context` references: `$name` → lookup by task name, `$0` → lookup by index
2. Validate no circular dependencies (topological sort)
3. Build `Task.builder().context(resolvedTask)` for each dependency
4. If any task has context dependencies and no explicit workflow, infer `PARALLEL`

### 5.6 SubscriptionManager

Per-session event filter.

```java
public class SubscriptionManager {
    private final ConcurrentHashMap<String, Subscription> subscriptions;  // sessionId → filter

    public void subscribe(String sessionId, Set<String> eventTypes, String runId);
    public void unsubscribe(String sessionId);
    public boolean shouldDeliver(String sessionId, String eventType, String runId);
}
```

**Integration with ConnectionManager.broadcast():**
```java
// Before (current):
sessions.forEach(session -> session.send(json));

// After:
sessions.forEach(session -> {
    if (subscriptionManager.shouldDeliver(session.id(), eventType, runId)) {
        session.send(json);
    }
});
```

---

## 6. WebDashboard Builder Changes

```java
WebDashboard dashboard = WebDashboard.builder()
    .port(7329)
    .host("localhost")
    .reviewTimeout(Duration.ofMinutes(10))
    .onTimeout(OnTimeoutAction.CONTINUE)
    // --- New fields ---
    .toolCatalog(ToolCatalog.builder()
        .tool("web_search", webSearchTool)
        .tool("calculator", calculatorTool)
        .build())
    .modelCatalog(ModelCatalog.builder()
        .model("sonnet", sonnetModel)
        .model("opus", opusModel)
        .model("haiku", haikuModel)
        .build())
    .maxConcurrentRuns(5)          // default: 5
    .maxRetainedRuns(100)          // completed runs kept in memory for queries
    .build();

// The ensemble wires everything together:
Ensemble.builder()
    .chatLanguageModel(sonnetModel)
    .webDashboard(dashboard)
    .task(Task.builder().name("researcher").description("Research {topic}").tools(webSearchTool).build())
    .task(Task.builder().name("writer").description("Write a report").build())
    .build()
    .start(7329);

// Now external clients can:
// POST /api/runs {"inputs":{"topic":"AI safety"}}                    → Level 1
// POST /api/runs {"taskOverrides":{"writer":{"model":"opus"}}}       → Level 2
// POST /api/runs {"tasks":[...]}                                      → Level 3
```

---

## 7. Wire Protocol: New Messages

### 7.1 Client → Server

| Type | Record | Fields |
|------|--------|--------|
| `run_request` | `RunRequestMessage` | `requestId`, `inputs?`, `tasks?`, `taskOverrides?`, `options?`, `tags?` |
| `run_control` | `RunControlMessage` | `runId`, `action` ("cancel", "switch_model"), `model?` |
| `state_query` | `StateQueryMessage` | `requestId`, `query` ("runs", "run_detail", "capabilities"), `runId?`, `filter?` |
| `subscribe` | `SubscribeMessage` | `events[]`, `runId?` |

### 7.2 Server → Client

| Type | Record | Fields |
|------|--------|--------|
| `run_ack` | `RunAckMessage` | `requestId`, `runId`, `status`, `tasks`, `workflow` |
| `run_result` | `RunResultMessage` | `runId`, `status`, `outputs[]`, `durationMs`, `metrics` |
| `run_control_ack` | `RunControlAckMessage` | `runId`, `action`, `status`, `model?`, `previousModel?` |
| `state_response` | `StateResponseMessage` | `requestId`, `data` (polymorphic JSON) |
| `subscribe_ack` | `SubscribeAckMessage` | `events[]`, `runId?` |

All messages extend the existing `ClientMessage` / `ServerMessage` sealed interfaces with Jackson `@JsonTypeInfo` type discriminator.

---

## 8. Task Naming

Level 2 overrides and Level 3 context references need a way to identify tasks by name. Today, tasks have no explicit name — they're identified by position or description.

**Proposal:** Add an optional `name` field to `Task`:

```java
@Builder(toBuilder = true)
@Value
public class Task {
    String name;  // new, optional — used for API task matching and context references
    String description;
    // ... rest unchanged
}
```

- When `name` is set, Level 2 overrides match by exact name.
- When `name` is null, matching falls back to description prefix (first 50 chars, case-insensitive).
- `GET /api/capabilities` lists preconfigured tasks with their names.
- Level 3 dynamic tasks use `name` for `$name` context references.

---

## 9. Security Considerations

- All new REST endpoints inherit the existing localhost-only binding (unless `host` is `0.0.0.0`).
- `POST /api/runs` is the most sensitive endpoint — it can trigger LLM calls that cost money. Consider:
  - Rate limiting via `maxConcurrentRuns`
  - Optional API key authentication (future phase, not in initial implementation)
  - The `ToolCatalog` acts as an allowlist — only registered tools can be used
  - The `ModelCatalog` acts as an allowlist — only registered models can be referenced
- Dynamic task creation (Level 3) does not allow arbitrary code execution — task descriptions are just strings processed by the LLM. Tools are pre-registered Java objects.
- `outputSchema` in Level 3 is validated server-side — malformed schemas are rejected with 400.

---

## 10. Implementation Plan

### Phase 1: Catalogs + Level 1 Run Submission + State Query

**New files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/ToolCatalog.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/ModelCatalog.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/RunManager.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/RunState.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/RunRequestParser.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/RunAckMessage.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/RunResultMessage.java`

**Modified files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/WebDashboard.java` — add catalog fields to builder, create RunManager
- `agentensemble-web/src/main/java/net/agentensemble/web/WebSocketServer.java` — add REST endpoints: `POST /api/runs`, `GET /api/runs`, `GET /api/runs/{runId}`, `GET /api/capabilities`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/ServerMessage.java` — add RunAckMessage, RunResultMessage to sealed interface

**Tests:**
- `ToolCatalogTest`, `ModelCatalogTest` — resolution, listing, error cases
- `RunManagerTest` — submission, concurrency limit, completion, state tracking
- `RunRequestParserTest` — Level 1 input resolution
- REST endpoint integration tests (start WebDashboard, hit endpoints with HTTP client)

### Phase 2: Level 2+3 Parameterization + WebSocket Messages

**New files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/RunRequestMessage.java`

**Modified files:**
- `agentensemble-core/src/main/java/net/agentensemble/Task.java` — add optional `name` field
- `agentensemble-web/src/main/java/net/agentensemble/web/RunRequestParser.java` — Level 2 (task overrides) + Level 3 (dynamic tasks)
- `agentensemble-web/src/main/java/net/agentensemble/web/WebDashboard.java` — handle `RunRequestMessage` in WS handler
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/ClientMessage.java` — add RunRequestMessage

**Tests:**
- `RunRequestParserTest` — Level 2 override matching, Level 3 DAG building, error cases
- WS round-trip tests: send `run_request`, receive `run_ack` + events + `run_result`
- Dynamic task creation with context references
- Per-task model and tool overrides

### Phase 3: Run Control (Cancel + Model Switching)

**New files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/RunControlMessage.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/RunControlAckMessage.java`

**Modified files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/RunManager.java` — `cancelRun()`, `switchModel()`
- `agentensemble-web/src/main/java/net/agentensemble/web/WebSocketServer.java` — `POST /api/runs/{runId}/cancel`, `POST /api/runs/{runId}/model`
- `agentensemble-web/src/main/java/net/agentensemble/web/WebDashboard.java` — handle `RunControlMessage`
- `agentensemble-core/src/main/java/net/agentensemble/Ensemble.java` — add `switchToModel(ChatModel)` method
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/ClientMessage.java` — add RunControlMessage
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/ServerMessage.java` — add RunControlAckMessage

**Tests:**
- Cancel mid-run: submit 3-task sequential run, cancel after task 1, verify partial output
- Model switch: submit run, switch model after task 1, verify task 2 uses new model
- Error cases: cancel completed run (409), cancel unknown run (404)

### Phase 4: Event Subscription + SSE

**New files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/SubscriptionManager.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/SseHandler.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/SubscribeMessage.java`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/SubscribeAckMessage.java`

**Modified files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/ConnectionManager.java` — subscription-aware broadcast
- `agentensemble-web/src/main/java/net/agentensemble/web/WebSocketServer.java` — `GET /api/runs/{runId}/events` (SSE), handle `SubscribeMessage`
- `agentensemble-web/src/main/java/net/agentensemble/web/protocol/ClientMessage.java` — add SubscribeMessage

**Tests:**
- Subscribe to task events only, verify token events not received
- Subscribe to specific run, verify events from other runs not received
- SSE: connect, receive events, verify format
- SSE reconnection with `from` parameter
- Default behavior (no subscribe) unchanged

### Phase 5: REST Review + Context Injection + Tool Invocation

**Modified files:**
- `agentensemble-web/src/main/java/net/agentensemble/web/WebSocketServer.java` — `POST /api/reviews/{reviewId}`, `POST /api/runs/{runId}/inject`, `POST /api/tools/{name}/invoke`

**Tests:**
- REST review: submit run with review gate, submit decision via REST, verify run continues
- Context injection: submit run, inject directive via REST, verify it reaches agent prompt
- Tool invocation: call tool directly, verify output
- Error cases: unknown review ID, unknown tool name

---

## 11. Verification

1. **Backwards compatibility:** `./gradlew agentensemble-web:test` — all existing tests pass.
2. **Unit tests:** Each new class (ToolCatalog, ModelCatalog, RunManager, RunRequestParser, SubscriptionManager) has dedicated tests.
3. **Integration tests:** Each REST endpoint tested with Javalin test client.
4. **WebSocket tests:** New message round-trips tested with Java WebSocket client.
5. **E2E scenarios:**
   - Submit run via REST with Level 1 inputs, poll for completion, verify outputs.
   - Submit run via WebSocket with Level 3 dynamic tasks, receive streaming events + run_result.
   - Submit run, cancel mid-execution via REST, verify CANCELLED status and partial output.
   - Submit run, switch model mid-run, verify second task uses new model.
   - Connect SSE, observe real-time events, verify format matches WebSocket events.
   - Submit run with review gate, submit review via REST, verify continuation.
6. **Existing viz compatibility:** Open viz UI, submit run via REST, verify dashboard shows live events.
