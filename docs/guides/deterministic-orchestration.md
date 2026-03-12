# Deterministic Orchestration

AgentEnsemble can orchestrate purely deterministic (non-AI) workflows with the same
DAG execution, parallel phases, callbacks, guardrails, metrics, and review gates that
AI ensembles use -- but with zero LLM calls.

---

## The simplest possible pipeline

Every task in a deterministic pipeline has a `handler` -- a Java lambda that receives a
`TaskHandlerContext` and returns a `ToolResult`:

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.tool.ToolResult;

EnsembleOutput output = Ensemble.run(
    Task.builder()
        .description("Compute checksum of input data")
        .expectedOutput("SHA-256 hex string")
        .handler(ctx -> ToolResult.success(sha256(inputData)))
        .build(),
    Task.builder()
        .description("Store checksum to database")
        .expectedOutput("Storage confirmation")
        .handler(ctx -> {
            db.store(inputData, ctx.description());
            return ToolResult.success("stored");
        })
        .build()
);

System.out.println(output.getRaw()); // "stored"
```

`Ensemble.run(Task...)` requires all tasks to have handlers. If any task lacks one, a
clear error message points to the offending task and suggests using
`Ensemble.run(ChatModel, Task...)` for AI tasks.

---

## Passing data between tasks

Use `Task.builder().context(List.of(upstreamTask))` to declare a data dependency.
Inside the downstream handler, `ctx.contextOutputs()` delivers the prior task's output
as a `TaskOutput` whose raw string is accessed via `.getRaw()`:

```java
Task fetchTask = Task.builder()
    .description("Fetch product catalogue from REST API")
    .expectedOutput("JSON product list")
    .handler(ctx -> {
        String json = httpClient.get("https://api.example.com/products");
        return ToolResult.success(json);
    })
    .build();

Task parseTask = Task.builder()
    .description("Parse product list into structured records")
    .expectedOutput("CSV of product records")
    .context(List.of(fetchTask))
    .handler(ctx -> {
        String json = ctx.contextOutputs().get(0).getRaw();
        String csv  = jsonToCsv(json);
        return ToolResult.success(csv);
    })
    .build();

Task storeTask = Task.builder()
    .description("Write CSV to data warehouse")
    .expectedOutput("Row count written")
    .context(List.of(parseTask))
    .handler(ctx -> {
        String csv  = ctx.contextOutputs().get(0).getRaw();
        int rows = warehouse.bulkInsert(csv);
        return ToolResult.success(rows + " rows inserted");
    })
    .build();

EnsembleOutput output = Ensemble.run(fetchTask, parseTask, storeTask);
System.out.println(output.getRaw()); // "1234 rows inserted"
```

The full output of every task is accessible in `output.getTaskOutputs()`:

```java
for (TaskOutput taskOutput : output.getTaskOutputs()) {
    System.out.printf("  %s -> %s%n",
        taskOutput.getTaskDescription(),
        taskOutput.getRaw());
}
```

---

## Reading multiple upstream outputs

A task can declare multiple context dependencies. The outputs are delivered in
declaration order:

```java
Task serviceA = Task.builder()
    .description("Call service A")
    .handler(ctx -> ToolResult.success(serviceA.getData()))
    .build();

Task serviceB = Task.builder()
    .description("Call service B")
    .handler(ctx -> ToolResult.success(serviceB.getData()))
    .build();

Task aggregator = Task.builder()
    .description("Merge results from A and B")
    .context(List.of(serviceA, serviceB))
    .handler(ctx -> {
        String dataA = ctx.contextOutputs().get(0).getRaw(); // serviceA output
        String dataB = ctx.contextOutputs().get(1).getRaw(); // serviceB output
        return ToolResult.success(merge(dataA, dataB));
    })
    .build();
```

When `aggregator` is added to an ensemble alongside `serviceA` and `serviceB`, the
framework infers a PARALLEL workflow automatically -- `serviceA` and `serviceB` run
concurrently, and `aggregator` starts only after both complete:

```java
EnsembleOutput output = Ensemble.builder()
    .task(serviceA)
    .task(serviceB)
    .task(aggregator)
    .build()
    .run();
```

---

## Parallel independent tasks

For tasks with no data dependency on each other, declare them in an ensemble with
`workflow(Workflow.PARALLEL)`:

```java
EnsembleOutput output = Ensemble.builder()
    .task(Task.builder()
        .description("Send email notification")
        .handler(ctx -> { email.send(); return ToolResult.success("sent"); })
        .build())
    .task(Task.builder()
        .description("Post Slack message")
        .handler(ctx -> { slack.post(); return ToolResult.success("posted"); })
        .build())
    .task(Task.builder()
        .description("Update metrics dashboard")
        .handler(ctx -> { metrics.record(); return ToolResult.success("recorded"); })
        .build())
    .workflow(Workflow.PARALLEL)
    .build()
    .run();
```

All three tasks start immediately and run concurrently on virtual threads.

---

## Named workstreams with phases

Use phases when the pipeline has distinct stages with named logical groupings:

```java
Task extract   = Task.builder().description("Extract").handler(ctx -> ...).build();
Task transform = Task.builder().description("Transform")
    .context(List.of(extract)).handler(ctx -> ...).build();
Task load      = Task.builder().description("Load")
    .context(List.of(transform)).handler(ctx -> ...).build();

Phase extractPhase = Phase.of("extract", extract);
Phase transformPhase = Phase.builder()
    .name("transform")
    .task(transform)
    .after(extractPhase)
    .build();
Phase loadPhase = Phase.builder()
    .name("load")
    .task(load)
    .after(transformPhase)
    .build();

EnsembleOutput output = Ensemble.builder()
    .phase(extractPhase)
    .phase(transformPhase)
    .phase(loadPhase)
    .build()
    .run();

// Per-phase results are accessible by name
List<TaskOutput> extractResults   = output.getPhaseOutputs().get("extract");
List<TaskOutput> transformResults = output.getPhaseOutputs().get("transform");
List<TaskOutput> loadResults      = output.getPhaseOutputs().get("load");
```

Independent phases (with no `after()` dependency between them) run in parallel automatically.

---

## Observability: callbacks

All task lifecycle callbacks work on deterministic tasks exactly as they do for AI tasks:

```java
EnsembleOutput output = Ensemble.builder()
    .task(fetchTask)
    .task(transformTask)
    .onTaskStart(e -> log.info("Starting: {}", e.getTaskDescription()))
    .onTaskComplete(e -> log.info("Completed: {} in {}",
        e.getTaskDescription(), e.getDuration()))
    .onTaskFailed(e -> log.error("Failed: {}", e.getTaskDescription(), e.getException()))
    .build()
    .run();
```

---

## Guardrails on deterministic tasks

Input and output guardrails enforce pre/post conditions on handler tasks:

```java
Task validate = Task.builder()
    .description("Validate incoming payload")
    .handler(ctx -> ToolResult.success(process(ctx.description())))
    .inputGuardrail(input -> {
        if (input.text().isBlank()) return GuardrailResult.failure("Input must not be blank");
        return GuardrailResult.success();
    })
    .outputGuardrail(output -> {
        if (output.text().length() > MAX_SIZE) return GuardrailResult.failure("Output too large");
        return GuardrailResult.success();
    })
    .build();
```

---

## Handling failures

When a handler returns `ToolResult.failure(...)`, the ensemble throws a
`TaskExecutionException`. The exception contains all outputs from tasks that completed
before the failure:

```java
try {
    Ensemble.run(step1, step2, step3);
} catch (TaskExecutionException e) {
    log.error("Pipeline failed at task: {}", e.getTaskDescription());
    log.error("Completed before failure: {} tasks", e.getCompletedTaskOutputs().size());
}
```

---

## Mixing deterministic and AI tasks

Deterministic and AI tasks compose freely in the same ensemble. Only tasks without a
handler require a `ChatModel`:

```java
Task fetchData = Task.builder()
    .description("Fetch raw user feedback from API")
    .handler(ctx -> ToolResult.success(api.fetchFeedback()))
    .build();

Task summarize = Task.builder()
    .description("Summarise the feedback into three bullet points")
    .expectedOutput("Three bullet point summary")
    .context(List.of(fetchData))
    .build(); // AI task -- will use the ensemble-level model

Task storeResult = Task.builder()
    .description("Store summarised feedback to database")
    .context(List.of(summarize))
    .handler(ctx -> {
        db.store(ctx.contextOutputs().get(0).getRaw());
        return ToolResult.success("stored");
    })
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)    // required for the AI task only
    .task(fetchData)
    .task(summarize)
    .task(storeResult)
    .build()
    .run();
```

---

## Builder reference for deterministic-only ensembles

| Method | Required? | Notes |
|--------|-----------|-------|
| `task(...).handler(...)` | Yes (all tasks) | The functional handler to execute |
| `chatLanguageModel(model)` | No | Not needed when all tasks have handlers |
| `workflow(Workflow.SEQUENTIAL)` | No | Inferred as SEQUENTIAL when no context deps exist |
| `workflow(Workflow.PARALLEL)` | No | Use when tasks should run concurrently |
| `onTaskStart(...)` | No | Callback fired before each task |
| `onTaskComplete(...)` | No | Callback fired after each task succeeds |
| `onTaskFailed(...)` | No | Callback fired when a task fails |
| `phase(...)` | No | Use instead of `task()` for named workstreams |
| `reviewHandler(...)` | No | Human-in-the-loop review gates |
