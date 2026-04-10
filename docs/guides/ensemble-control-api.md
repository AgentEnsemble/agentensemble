# Ensemble Control API

The Ensemble Control API adds HTTP-based and WebSocket run management to the live dashboard.
External systems (CI pipelines, orchestrators, custom UIs) can submit ensemble runs, query their
status, and discover available capabilities -- all without compiling Java code.

This guide covers **Phases 1 and 2**:

- **Phase 1**: Level 1 run submission (template + input variables), state queries, capabilities discovery.
- **Phase 2**: Task naming (`Task.name`), Level 2 per-task overrides, Level 3 dynamic task creation,
  WebSocket `run_request` message.

Phases 3-5 add run control (cancel, model switch), SSE streaming, and REST review decisions.

## Overview

```
POST /api/runs          Submit a run with input variables
GET  /api/runs          List recent runs (filterable by status, tag)
GET  /api/runs/{runId}  Get full run detail (status, task outputs, metrics)
GET  /api/capabilities  List registered tools, models, preconfigured tasks
```

All endpoints are on the same Javalin server as the WebSocket dashboard -- no new port or process
required.

## Setup

### 1. Configure the dashboard

Add `toolCatalog`, `modelCatalog`, and (optionally) concurrency limits to `WebDashboard.builder()`:

```java
ToolCatalog tools = ToolCatalog.builder()
    .tool("web_search", webSearchTool)
    .tool("calculator", calculatorTool)
    .build();

ModelCatalog models = ModelCatalog.builder()
    .model("sonnet", claudeSonnetModel)
    .model("haiku", claudeHaikuModel)
    .build();

WebDashboard dashboard = WebDashboard.builder()
    .port(7329)
    .toolCatalog(tools)
    .modelCatalog(models)
    .maxConcurrentRuns(5)          // default: 5
    .maxRetainedCompletedRuns(100) // default: 100
    .build();
```

### 2. Wire the ensemble

The template ensemble -- whose tasks and model define what can run -- is wired via
`Ensemble.builder().webDashboard(dashboard)`:

```java
Ensemble.builder()
    .chatLanguageModel(claudeSonnetModel)
    .webDashboard(dashboard)
    .task(Task.builder()
        .description("Research {topic} focusing on recent developments in {year}")
        .tools(webSearchTool)
        .build())
    .task(Task.builder()
        .description("Write a concise executive summary of the research")
        .build())
    .build()
    .start(7329);
```

After `start()` (long-running mode) or `run()` (one-shot), the REST endpoints are available.

## Submitting a run

### Level 1: Template inputs

Send the input variables to substitute into `{placeholder}` patterns in task descriptions:

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

Response (`202 Accepted`):

```json
{
  "runId": "run-7f3a2b",
  "status": "ACCEPTED",
  "tasks": 2,
  "workflow": "SEQUENTIAL"
}
```

`tags` are arbitrary key-value metadata attached to the run for filtering and querying.

### Empty body

An empty body (or `{}`) submits the template ensemble with no input substitution:

```
POST /api/runs
Content-Type: application/json
{}
```

### Error responses

| HTTP | Error code | Cause |
|------|-----------|-------|
| 400 | `BAD_REQUEST` | Invalid or unparseable JSON body |
| 429 | `CONCURRENCY_LIMIT` | `maxConcurrentRuns` reached; includes `retryAfterMs` |
| 503 | `NOT_CONFIGURED` | No template ensemble wired via `webDashboard()` |

## Querying runs

### List all runs

```
GET /api/runs
```

```json
{
  "runs": [
    {
      "runId": "run-7f3a2b",
      "status": "RUNNING",
      "startedAt": "2025-03-15T10:30:00Z",
      "durationMs": null,
      "taskCount": 2,
      "completedTasks": 1,
      "workflow": "SEQUENTIAL",
      "tags": { "triggeredBy": "ci-pipeline" }
    }
  ],
  "total": 1
}
```

### Filter by status

```
GET /api/runs?status=COMPLETED
GET /api/runs?status=RUNNING
GET /api/runs?status=FAILED
```

Valid statuses: `ACCEPTED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`, `REJECTED`.

### Filter by tag

```
GET /api/runs?tag=triggeredBy:ci-pipeline
GET /api/runs?tag=environment:staging
```

### Get run detail

```
GET /api/runs/{runId}
```

```json
{
  "runId": "run-7f3a2b",
  "status": "COMPLETED",
  "startedAt": "2025-03-15T10:30:00Z",
  "completedAt": "2025-03-15T10:30:11Z",
  "durationMs": 11340,
  "workflow": "SEQUENTIAL",
  "inputs": { "topic": "AI safety", "year": "2025" },
  "tags": { "triggeredBy": "ci-pipeline" },
  "tasks": [
    {
      "taskDescription": "Research AI safety focusing on recent developments in 2025",
      "output": "## AI Safety Overview\n...",
      "durationMs": 8200,
      "tokenCount": 10150,
      "toolCallCount": 5
    },
    {
      "taskDescription": "Write a concise executive summary of the research",
      "output": "# Executive Summary\n...",
      "durationMs": 3100,
      "tokenCount": 5080,
      "toolCallCount": 0
    }
  ],
  "metrics": {
    "totalTokens": 15230,
    "totalToolCalls": 5
  }
}
```

| HTTP | Error code | Cause |
|------|-----------|-------|
| 404 | `RUN_NOT_FOUND` | Unknown run ID or run was evicted |

## Querying capabilities

```
GET /api/capabilities
```

Returns registered tools, models, and the preconfigured tasks from the template ensemble:

```json
{
  "tools": [
    { "name": "web_search", "description": "Search the web using Google" },
    { "name": "calculator", "description": "Evaluate mathematical expressions" }
  ],
  "models": [
    { "alias": "sonnet", "provider": "anthropic" },
    { "alias": "haiku", "provider": "anthropic" }
  ],
  "preconfiguredTasks": [
    { "description": "Research {topic} focusing on recent developments in {year}" },
    { "description": "Write a concise executive summary of the research" }
  ]
}
```

If no catalog or template ensemble is configured, each list is empty (`[]`).

## Concurrency

`maxConcurrentRuns` limits how many runs execute simultaneously. When the limit is reached,
new submissions return HTTP 429 immediately. The `retryAfterMs` field in the response body
gives a rough hint for when to retry.

```java
WebDashboard.builder()
    .maxConcurrentRuns(10)         // allow 10 parallel runs
    .maxRetainedCompletedRuns(500) // keep 500 completed runs in memory
    .build();
```

Completed, failed, and cancelled runs are retained in memory for querying until the
`maxRetainedCompletedRuns` cap is reached, at which point the oldest are evicted.
Active runs (ACCEPTED and RUNNING) are never evicted.

## Live events

While a run executes, the existing WebSocket dashboard continues to stream all events
(`task_started`, `tool_called`, `token`, `llm_iteration_started`, etc.) to connected browsers.
This is unchanged -- the REST Control API and WebSocket dashboard work together transparently.

---

## Task naming (Phase 2)

Give tasks an optional logical name so they can be referenced in Level 2 overrides and Level 3
context expressions:

```java
Task.builder()
    .name("researcher")            // new -- optional but recommended for API use
    .description("Research {topic} developments in {year}")
    .expectedOutput("A detailed report")
    .tools(webSearchTool)
    .build()
```

- Names must be non-blank when set.
- `GET /api/capabilities` returns task names alongside descriptions.
- Level 2 override keys match by **exact name** first, then by **description prefix** (first
  50 chars, case-insensitive).
- Level 3 context references use `$name` syntax.

---

## Level 2: Per-task overrides (Phase 2)

Override specific fields of the template ensemble's tasks at runtime -- no recompilation needed:

```json
POST /api/runs
{
  "inputs": { "topic": "AI safety" },
  "taskOverrides": {
    "researcher": {
      "description": "Research {topic} focusing on EU AI Act compliance",
      "expectedOutput": "A regulatory analysis report with citations",
      "model": "sonnet",
      "maxIterations": 15,
      "additionalContext": "The EU AI Act was formally adopted in March 2024.",
      "tools": {
        "add": ["web_search"],
        "remove": ["calculator"]
      }
    }
  }
}
```

The override key (`"researcher"`) is matched against the template ensemble's task names. If no task
with that name exists, the request is rejected with 400.

**Supported override fields:**

| Field | Type | Semantics |
|---|---|---|
| `description` | string | Replaces task description. Supports `{variable}` templates. |
| `expectedOutput` | string | Replaces expected output. |
| `model` | string | Model alias resolved from `ModelCatalog`. Replaces `chatLanguageModel`. |
| `maxIterations` | int | Replaces max tool-call iteration count. |
| `additionalContext` | string | Appended to the task description (not replacing). |
| `tools.add` | string[] | Tool names resolved from `ToolCatalog` and added to the task's tool list. |
| `tools.remove` | string[] | Tool names resolved from `ToolCatalog` and removed from the task's tool list. |

**Task matching rules:**

1. Exact `name` match (case-insensitive).
2. Fallback: first 50 characters of task `description` compared case-insensitively.

The original task objects are never mutated -- `Task.toBuilder()` creates modified copies.

---

## Level 3: Dynamic task creation (Phase 2)

Define an entirely new task list at runtime without changing any Java code:

```json
POST /api/runs
{
  "tasks": [
    {
      "name": "researcher",
      "description": "Research the competitive landscape for {product}",
      "expectedOutput": "A competitive analysis identifying 5 key competitors",
      "tools": ["web_search"],
      "model": "sonnet",
      "maxIterations": 20
    },
    {
      "name": "writer",
      "description": "Write an executive brief based on the research",
      "expectedOutput": "A 1-page executive summary suitable for C-suite",
      "context": ["$researcher"],
      "model": "sonnet"
    }
  ],
  "inputs": { "product": "AgentEnsemble" }
}
```

When `tasks` is provided, the template ensemble's task list is replaced with the dynamic list.
The template's model, catalogs, and other settings (rate limits, workflow, etc.) are preserved.

**Task definition fields:**

| Field | Required | Description |
|---|---|---|
| `name` | No | Logical name. Used for `$name` context references. |
| `description` | **Yes** | What the agent should do. Supports `{variable}` templates. |
| `expectedOutput` | No | Expected output. Defaults to `Task.DEFAULT_EXPECTED_OUTPUT`. |
| `tools` | No | Tool names resolved from `ToolCatalog`. |
| `model` | No | Model alias resolved from `ModelCatalog`. Falls back to template default. |
| `maxIterations` | No | Max ReAct loop iterations. Default: 25. |
| `context` | No | `$name` or `$N` (0-based index) references to predecessor tasks. |
| `outputSchema` | No | JSON Schema injected as structured output instructions into `expectedOutput`. |
| `additionalContext` | No | Extra text appended to the task description. |

**Context DAG (`context` field):**

```json
"context": ["$researcher"]   // by name
"context": ["$0"]            // by index (0-based position in the tasks array)
```

- Circular dependencies are detected and rejected (400).
- Unknown names or out-of-bounds indices are rejected (400).
- When context dependencies exist and no explicit workflow is set, `PARALLEL` (DAG-based) is
  automatically inferred.

---

## WebSocket run submission (Phase 2)

As an alternative to REST, WebSocket clients can submit runs using the `run_request` message.
This lets browser-based UIs and long-lived WS clients kick off runs without an additional HTTP
round-trip.

**Client sends:**

```json
{
  "type": "run_request",
  "requestId": "req-1",
  "inputs": { "topic": "AI safety" },
  "tasks": [...],
  "taskOverrides": {...},
  "options": { "maxToolOutputLength": 5000 },
  "tags": { "env": "staging" }
}
```

All fields except `type` are optional. Level detection: if `tasks` is present, Level 3 is used;
else if `taskOverrides` is present, Level 2 is used; otherwise Level 1.

**Server responds immediately (`run_ack`):**

```json
{
  "type": "run_ack",
  "requestId": "req-1",
  "runId": "run-7f3a2b",
  "status": "ACCEPTED",
  "tasks": 2,
  "workflow": "SEQUENTIAL"
}
```

**On completion, the server sends `run_result` to the originating session only:**

```json
{
  "type": "run_result",
  "runId": "run-7f3a2b",
  "status": "COMPLETED",
  "outputs": [
    { "taskName": "researcher", "output": "...", "durationMs": 8200 },
    { "taskName": "writer", "output": "...", "durationMs": 3100 }
  ],
  "durationMs": 11340,
  "metrics": { "totalTokens": 15230, "totalToolCalls": 7 }
}
```

The `run_result` is targeted to the submitting session only. The existing `ensemble_completed`
broadcast continues to go to all connected clients (backwards compatible).

When no template ensemble is configured (`setEnsemble()` has not been called), the server
immediately responds with a `run_ack` carrying `status: "REJECTED"`.

---

## Current limitations (Phases 3-5 planned)

- **No run cancellation**: mid-run cancel is planned for Phase 3.
- **No model switch mid-run**: planned for Phase 3.
- **No SSE streaming**: event streaming for HTTP clients is planned for Phase 4.
- **No REST review decisions**: REST-based human review is planned for Phase 5.

## See also

- [Live Dashboard guide](live-dashboard.md)
- [Long-Running Ensembles guide](long-running-ensembles.md)
- [Ensemble Configuration reference](../reference/ensemble-configuration.md)
- [Design doc 28: Ensemble Control API](../design/28-ensemble-control-api.md)
