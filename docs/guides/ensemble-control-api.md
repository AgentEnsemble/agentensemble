# Ensemble Control API

The Ensemble Control API adds HTTP-based run management to the live dashboard. External systems
(CI pipelines, orchestrators, custom UIs) can submit ensemble runs, query their status, and
discover available capabilities -- all without compiling Java code.

This guide covers **Phase 1**: Level 1 run submission (template + input variables), state queries,
and capabilities discovery. Phases 2-5 add per-task overrides, dynamic task creation, run control,
SSE streaming, and REST review decisions.

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

## Phase 1 limitations

- **Level 1 only**: input variable substitution into the existing template ensemble's tasks.
  Level 2 (per-task overrides) and Level 3 (dynamic task creation) are planned for Phase 2.
- **No run cancellation**: mid-run cancel is planned for Phase 3.
- **No SSE streaming**: event streaming for HTTP clients is planned for Phase 4.
- **No REST review decisions**: REST-based human review is planned for Phase 5.

## See also

- [Live Dashboard guide](live-dashboard.md)
- [Long-Running Ensembles guide](long-running-ensembles.md)
- [Ensemble Configuration reference](../reference/ensemble-configuration.md)
- [Design doc 28: Ensemble Control API](../design/28-ensemble-control-api.md)
