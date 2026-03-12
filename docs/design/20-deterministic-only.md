# 20 -- Deterministic-Only Orchestration

## Overview

AgentEnsemble is not exclusively an AI orchestrator. Because every execution pathway
in the framework -- sequential and parallel task execution, phase DAG scheduling, context
passing, callbacks, guardrails, metrics, and review gates -- is independent of the LLM
layer, the same framework that coordinates teams of AI agents can be used with **zero AI
calls** to orchestrate purely deterministic (non-AI) task pipelines.

A deterministic-only ensemble uses only `Task.builder().handler(...)` tasks. No
`ChatModel`, no agent synthesis, no LLM round-trips.

---

## When to use this pattern

Use a deterministic-only ensemble when you need to sequence or parallelize Java functions
and want the framework to handle:

- Passing the output of one step to the input of the next via `context()`
- Running independent steps concurrently (PARALLEL workflow or parallel phases)
- Calling callbacks on task start/complete/fail for observability
- Enforcing input/output guardrails on each step
- Human-in-the-loop review gates at key checkpoints
- Execution metrics and tracing without touching an LLM

Typical use cases:

| Pattern | Example |
|---------|---------|
| ETL pipelines | Fetch CSV, parse rows, validate, write to database |
| API chaining | Call REST API A, parse response, call REST API B with result |
| Data transformation | Read JSON, apply business rules, format output |
| Multi-step file processing | Read file, transform content, write result |
| Batch orchestration | Fan-out parallel fetches, then merge results |
| Hybrid AI + deterministic | Deterministic pre-processing, AI summarization, deterministic post-processing |

---

## The zero-ceremony API

For deterministic-only pipelines, use the `Ensemble.run(Task...)` static factory:

```java
Task fetchTask = Task.builder()
    .description("Fetch product data from API")
    .expectedOutput("JSON product data")
    .handler(ctx -> ToolResult.success(apiClient.fetchProduct()))
    .build();

Task transformTask = Task.builder()
    .description("Transform product data into display format")
    .expectedOutput("Formatted product line")
    .context(List.of(fetchTask))
    .handler(ctx -> {
        String json = ctx.contextOutputs().get(0).getRaw();
        return ToolResult.success(productFormatter.format(json));
    })
    .build();

EnsembleOutput result = Ensemble.run(fetchTask, transformTask);
```

No `ChatModel`, no `.chatLanguageModel(model)`, no LLM dependency whatsoever.

This factory validates that all supplied tasks have handlers. If any task lacks a handler
and also lacks an LLM source, a clear `IllegalArgumentException` is thrown pointing to
the offending task.

---

## Data sharing between tasks

Output from one task flows to downstream tasks via `Task.builder().context(List.of(upstreamTask))`.
Inside the handler, `TaskHandlerContext.contextOutputs()` returns the outputs of all
declared context tasks in declaration order:

```java
Task step1 = Task.builder()
    .description("Fetch configuration")
    .expectedOutput("Config JSON")
    .handler(ctx -> ToolResult.success("{\"timeout\":30,\"retries\":3}"))
    .build();

Task step2 = Task.builder()
    .description("Apply configuration to deployment")
    .expectedOutput("Deployment result")
    .context(List.of(step1))
    .handler(ctx -> {
        // The raw string output of step1
        String config = ctx.contextOutputs().get(0).getRaw();
        deployer.deploy(config);
        return ToolResult.success("deployed");
    })
    .build();
```

Multiple upstream tasks can be declared:

```java
Task step3 = Task.builder()
    .description("Aggregate results")
    .context(List.of(step1, step2))
    .handler(ctx -> {
        String configJson  = ctx.contextOutputs().get(0).getRaw();
        String deployResult = ctx.contextOutputs().get(1).getRaw();
        return ToolResult.success(aggregate(configJson, deployResult));
    })
    .build();
```

---

## Parallel execution

When two or more tasks have no dependency on each other, set `workflow(Workflow.PARALLEL)`:

```java
Task fetchA = Task.builder()
    .description("Fetch from service A")
    .handler(ctx -> ToolResult.success(serviceA.fetch()))
    .build();

Task fetchB = Task.builder()
    .description("Fetch from service B")
    .handler(ctx -> ToolResult.success(serviceB.fetch()))
    .build();

Task merge = Task.builder()
    .description("Merge results from A and B")
    .context(List.of(fetchA, fetchB))
    .handler(ctx -> {
        String a = ctx.contextOutputs().get(0).getRaw();
        String b = ctx.contextOutputs().get(1).getRaw();
        return ToolResult.success(merger.merge(a, b));
    })
    .build();

// context() dependency on fetchA and fetchB causes PARALLEL to be inferred automatically
EnsembleOutput output = Ensemble.builder()
    .task(fetchA)
    .task(fetchB)
    .task(merge)
    .build()
    .run();
```

The framework infers PARALLEL workflow automatically when any task has a `context()`
dependency on another ensemble task. `fetchA` and `fetchB` execute concurrently; `merge`
waits for both.

---

## Phase-based deterministic pipelines

For named workstreams with explicit dependencies, use phases:

```java
Task ingest  = Task.builder().description("Ingest").handler(...).build();
Task process = Task.builder().description("Process").handler(...).build();
Task report  = Task.builder().description("Report").handler(...).build();

Phase ingestPhase = Phase.of("ingest", ingest);
Phase processPhase = Phase.builder()
    .name("process")
    .task(process)
    .after(ingestPhase)
    .build();
Phase reportPhase = Phase.builder()
    .name("report")
    .task(report)
    .after(processPhase)
    .build();

EnsembleOutput output = Ensemble.builder()
    .phase(ingestPhase)
    .phase(processPhase)
    .phase(reportPhase)
    .build()
    .run();
```

Independent phases (those not in each other's `after()` lists) run concurrently. Dependent
phases wait for all declared predecessors to complete before starting.

Cross-phase data sharing works the same as within-phase context: declare `context(List.of(taskFromPriorPhase))`
on a task in a later phase, and the framework passes the prior task's output through
`TaskHandlerContext.contextOutputs()`.

---

## Builder API (full control)

For scenarios that need callbacks, guardrails, or other features, use the builder:

```java
EnsembleOutput output = Ensemble.builder()
    .task(fetchTask)
    .task(transformTask)
    .workflow(Workflow.SEQUENTIAL)
    .onTaskStart(e -> log.info("Starting: {}", e.getTaskDescription()))
    .onTaskComplete(e -> metrics.record(e.getDuration()))
    .build()
    .run();
```

No `chatLanguageModel(model)` needed when all tasks have handlers.

---

## Validation rules

When all tasks are deterministic:

- No `chatLanguageModel` is required at the ensemble or task level
- `rateLimit` must not be set (it has no meaning without a model)
- `workflow(Workflow.HIERARCHICAL)` cannot be used (the Manager agent requires an LLM)
- All other workflows (SEQUENTIAL, PARALLEL) and phase-based DAG execution work fully

Mixed ensembles (some handler tasks, some AI tasks) require a `chatLanguageModel` for
the AI tasks but not for the handler tasks.

---

## How it works internally

Deterministic tasks bypass the LLM entirely. Instead of going through `AgentExecutor`
(which calls the LLM), handler tasks are routed to `DeterministicTaskExecutor`, which:

1. Evaluates input guardrails (if configured)
2. Builds a `TaskHandlerContext` with resolved description and upstream context outputs
3. Invokes the `TaskHandler` functional interface
4. Evaluates output guardrails (if configured)
5. Writes to memory scopes (if configured)
6. Fires task start/complete/fail callbacks
7. Returns a `TaskOutput` with `agentRole = "(deterministic)"` and `toolCallCount = 0`

This path is identical to the AI path in every lifecycle concern except the LLM call
itself. Guardrails, review gates, callbacks, memory, metrics, and tracing all apply
equally to deterministic tasks.

---

## Relationship to the existing deterministic tasks feature

Deterministic-only orchestration is an application of the existing `TaskHandler` feature
(documented in design doc 18 and in the API reference). The difference is scope:

| Feature | What it adds |
|---------|-------------|
| Deterministic tasks (doc 18) | Individual tasks that skip the LLM in an otherwise AI-backed ensemble |
| Deterministic-only orchestration (this doc) | Entire ensembles with no LLM, positioned as a general-purpose workflow engine |

The framework supports both patterns with the same API surface and no special configuration.
