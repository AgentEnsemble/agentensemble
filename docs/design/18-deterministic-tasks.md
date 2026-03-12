# Deterministic Tasks

## Overview

Not every task in an ensemble requires AI reasoning. Sometimes you need to:

- Call a REST API and pass the raw response downstream
- Read a file or database and forward the contents
- Transform or aggregate outputs from prior AI tasks
- Run a `ToolPipeline` without LLM round-trips

For these cases, routing through the LLM wastes tokens, adds latency, and introduces
non-determinism where none is needed. **Deterministic tasks** let you execute any Java
function directly as a task step, bypassing the agent and the ReAct tool-calling loop.

---

## API Design

### `TaskHandler` Interface

```java
@FunctionalInterface
public interface TaskHandler {
    ToolResult execute(TaskHandlerContext context);
}
```

A `TaskHandler` is a functional interface that receives a `TaskHandlerContext` and returns
a `ToolResult`. Use `ToolResult.success(String)` for normal output and
`ToolResult.failure(String)` to signal an error.

### `TaskHandlerContext` Record

```java
public record TaskHandlerContext(
    String description,
    String expectedOutput,
    List<TaskOutput> contextOutputs
) {}
```

The context carries:
- The task's **resolved** description and expected output (with `{variable}` placeholders
  already substituted).
- The outputs of all tasks declared in `Task.context()` that completed before this task.

### Builder Overloads on `Task`

Two builder overloads configure a handler:

```java
// Lambda overload -- full context access
task.builder().handler(TaskHandler handler)

// AgentTool overload -- wraps an existing tool
task.builder().handler(AgentTool tool)
```

The `AgentTool` overload resolves the tool input as:
- Last context output's raw text, if context outputs are present
- The task description otherwise

---

## Usage

### Level 1: Lambda Handler

```java
Task fetchPrices = Task.builder()
    .description("Fetch current stock prices")
    .expectedOutput("JSON with stock prices")
    .handler(ctx -> ToolResult.success(httpClient.get("https://api.example.com/prices")))
    .build();
```

### Level 2: Wrap an Existing `AgentTool`

```java
// httpTool.execute() is called with the task description as input
Task fetch = Task.builder()
    .description("https://api.example.com/prices")
    .expectedOutput("HTTP response body")
    .handler(httpTool)   // AgentTool overload
    .build();
```

### Level 3: Use a `ToolPipeline`

Since `ToolPipeline` implements `AgentTool`, it works directly with the `AgentTool` overload:

```java
ToolPipeline pipeline = ToolPipeline.of(httpTool, jsonParserTool);

Task fetchAndParse = Task.builder()
    .description("https://api.example.com/prices")
    .expectedOutput("Parsed stock data")
    .handler(pipeline)
    .build();
```

### Mixed AI and Deterministic Tasks

```java
// Deterministic: call REST API
Task fetchPrices = Task.builder()
    .description("Fetch current stock prices")
    .expectedOutput("JSON prices")
    .handler(ctx -> ToolResult.success(apiClient.getPrices()))
    .build();

// AI-backed: analyze the data
Task analyze = Task.builder()
    .description("Analyze the stock prices and identify trends")
    .expectedOutput("Investment recommendations")
    .chatLanguageModel(model)
    .context(List.of(fetchPrices))
    .build();

// Deterministic: format the AI output
Task format = Task.builder()
    .description("Format the analysis as a report")
    .expectedOutput("Formatted HTML report")
    .context(List.of(analyze))
    .handler(ctx -> {
        String aiAnalysis = ctx.contextOutputs().get(0).getRaw();
        return ToolResult.success(ReportFormatter.toHtml(aiAnalysis));
    })
    .build();

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .tasks(List.of(fetchPrices, analyze, format))
    .build()
    .run();
```

---

## Execution Path

When a task has a `handler` set, the workflow executors (sequential and parallel)
invoke `DeterministicTaskExecutor` instead of `AgentExecutor`:

```
Ensemble.run()
  -> resolveAgents()    -- skips synthesis for handler tasks
  -> WorkflowExecutor.execute()
       -> task.getHandler() != null?
            YES: DeterministicTaskExecutor.execute()
            NO:  AgentExecutor.execute()
```

`DeterministicTaskExecutor` lifecycle:

1. Run **input guardrails** (if any)
2. Build `TaskHandlerContext` with resolved description, expected output, and context outputs
3. Call `handler.execute(context)` -- wrapped in try/catch
4. On `ToolResult.failure()` or exception: throw `AgentExecutionException`
5. Run **output guardrails** (if any)
6. Store output in declared **memory scopes** (if any)
7. Return `TaskOutput` with `agentRole = "(deterministic)"`, `toolCallCount = 0`

---

## Structured Output

If the task has `outputType` declared, the handler can provide a pre-typed Java object
via `ToolResult.success(text, typedValue)` to skip JSON deserialization:

```java
record PriceReport(String symbol, double price) {}

Task fetchPrices = Task.builder()
    .description("Fetch AAPL price")
    .expectedOutput("Price report")
    .outputType(PriceReport.class)
    .handler(ctx -> {
        PriceReport report = apiClient.getPrice("AAPL");
        return ToolResult.success(report.toString(), report);   // typed value provided
    })
    .build();

EnsembleOutput result = ...;
PriceReport report = result.getOutput(fetchPrices).getParsedOutput(PriceReport.class);
```

If `structuredOutput` is not set in the `ToolResult`, `parsedOutput` will be `null` in
the task output even when `outputType` is declared -- the handler is responsible for
providing the correctly typed value.

---

## Lifecycle Features

All lifecycle features work identically for deterministic and AI-backed tasks:

| Feature | Supported |
|---------|-----------|
| Input guardrails | Yes |
| Output guardrails | Yes |
| Before/after review gates | Yes |
| Memory scopes | Yes |
| Callbacks (`TaskStartEvent`, `TaskCompleteEvent`) | Yes |
| Context (prior task outputs) | Yes |
| Template variable substitution | Yes |
| Parallel workflow dependencies | Yes |

---

## Mutually Exclusive Fields

When `handler` is set, the following builder fields **must not** be used (they are
LLM-specific and will be rejected at build time with a `ValidationException`):

- `agent`
- `chatLanguageModel`
- `streamingChatLanguageModel`
- `tools`
- `maxIterations`
- `rateLimit`

The following fields **may** be used alongside `handler`:

- `context` (prior task dependencies)
- `outputType` (structured output via `ToolResult.success(text, typedValue)`)
- `inputGuardrails` / `outputGuardrails`
- `memoryScopes`
- `review` / `beforeReview`

---

## No LLM Required for Handler-Only Ensembles

An ensemble composed entirely of deterministic tasks does not require a
`chatLanguageModel`. Use the zero-ceremony `Ensemble.run(Task...)` factory:

```java
// No ChatModel needed -- all tasks are deterministic
EnsembleOutput output = Ensemble.run(fetchTask, parseTask, formatTask);
```

Or use the builder for full control over workflow, callbacks, and guardrails:

```java
EnsembleOutput output = Ensemble.builder()
    .task(fetchTask)
    .task(parseTask)
    .task(formatTask)
    .workflow(Workflow.SEQUENTIAL)
    .onTaskComplete(e -> log.info("Done: {}", e.taskDescription()))
    .build()
    .run();
```

The `Ensemble.run(Task...)` factory validates that all supplied tasks have handlers;
if any task lacks a handler and an LLM source, a descriptive `IllegalArgumentException`
is thrown.

Phase-based deterministic pipelines also require no LLM:

```java
Phase ingest   = Phase.of("ingest", ingestTask);
Phase process  = Phase.builder().name("process").task(processTask).after(ingest).build();
Phase publish  = Phase.builder().name("publish").task(publishTask).after(process).build();

EnsembleOutput output = Ensemble.builder()
    .phase(ingest)
    .phase(process)
    .phase(publish)
    .build()
    .run();
```

For a full treatment of this pattern (including data sharing between tasks, parallel
fan-out, and phase-based pipelines), see
[design doc 20 -- Deterministic-Only Orchestration](20-deterministic-only.md).

---

## Limitations

- **Hierarchical workflow**: Handler tasks are **not supported** in `Workflow.HIERARCHICAL`.
  The Manager agent delegates to worker agents via the LLM tool-calling loop; deterministic
  tasks have no agent and cannot be delegated to. A `ValidationException` is thrown at
  ensemble startup if a handler task is present in a hierarchical ensemble. Use
  `SEQUENTIAL` or `PARALLEL` workflow when mixing AI-backed and deterministic tasks.

- **Streaming**: Deterministic tasks produce no streaming tokens (no LLM is called).
  `TokenEvent` callbacks are not fired for handler tasks.
