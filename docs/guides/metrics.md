# Metrics and Observability

AgentEnsemble provides two layers of observability: **execution metrics** (token counts,
timing, costs) available on every run result, and **tool metrics** (pluggable per-tool
counters and timers via the `ToolMetrics` interface).

---

## Execution Metrics

Every `EnsembleOutput` carries an `ExecutionMetrics` object and every `TaskOutput` carries
a `TaskMetrics` object. These are populated automatically -- no configuration required.

```java
EnsembleOutput output = ensemble.run();

// Per-run totals
ExecutionMetrics metrics = output.getMetrics();
System.out.println("Total tokens:     " + metrics.getTotalTokens());
System.out.println("Input tokens:     " + metrics.getTotalInputTokens());
System.out.println("Output tokens:    " + metrics.getTotalOutputTokens());
System.out.println("LLM latency:      " + metrics.getTotalLlmLatency());
System.out.println("Tool exec time:   " + metrics.getTotalToolExecutionTime());
System.out.println("LLM calls:        " + metrics.getTotalLlmCallCount());

// Per-task breakdown
for (TaskOutput task : output.getTaskOutputs()) {
    TaskMetrics tm = task.getMetrics();
    System.out.printf("[%s] tokens=%d (in=%d out=%d) llm=%s tools=%s%n",
        task.getAgentRole(),
        tm.getTotalTokens(),
        tm.getInputTokens(),
        tm.getOutputTokens(),
        tm.getLlmLatency(),
        tm.getToolExecutionTime());
}
```

### Token counts

Token counts are sourced from `ChatResponse.tokenUsage()`. When the LLM provider
does **not** return usage metadata, token fields are `-1` (unknown) rather than `0`.
A value of `0` means zero tokens were used, not that the count is unavailable.

```java
long inputTokens = task.getMetrics().getInputTokens();
if (inputTokens < 0) {
    System.out.println("Token usage not available for this provider");
} else {
    System.out.println("Input tokens: " + inputTokens);
}
```

When any task in the run has unknown token counts, the aggregate
`ExecutionMetrics.getTotalTokens()` is also `-1`.

### Timing breakdown

`TaskMetrics` tracks four distinct timings:

| Field | Description |
|---|---|
| `llmLatency` | Cumulative time waiting for LLM responses across all ReAct iterations |
| `toolExecutionTime` | Cumulative time executing tools (excluding wait for LLM) |
| `promptBuildTime` | Time building system + user prompts before the first LLM call |
| `memoryRetrievalTime` | Time querying long-term and entity memory stores |

All durations use `java.time.Duration`. Use `.toMillis()`, `.toSeconds()`, or
`.toString()` to format them.

---

## Cost Estimation

Provide per-token rates and the framework multiplies them by the actual token counts.

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .costConfiguration(CostConfiguration.builder()
        .inputTokenRate(new BigDecimal("0.0000025"))   // $2.50 / 1M input tokens
        .outputTokenRate(new BigDecimal("0.0000100"))  // $10.00 / 1M output tokens
        .currency("USD")
        .build())
    .build();

EnsembleOutput output = ensemble.run();

// Per-run cost
CostEstimate total = output.getMetrics().getTotalCostEstimate();
if (total != null) {
    System.out.printf("Run cost: $%.6f (in=%.6f out=%.6f)%n",
        total.getTotalCost(),
        total.getInputCost(),
        total.getOutputCost());
}

// Per-task cost
for (TaskOutput task : output.getTaskOutputs()) {
    CostEstimate cost = task.getMetrics().getCostEstimate();
    if (cost != null) {
        System.out.printf("[%s] $%.6f%n", task.getAgentRole(), cost.getTotalCost());
    }
}
```

Cost estimation requires that the LLM provider returns token usage. When token counts
are `-1`, `getCostEstimate()` returns `null` rather than an incorrect zero.

---

## Execution Trace

Every run produces a complete `ExecutionTrace` -- a hierarchical record of every LLM
interaction, every tool call with its input and output, all prompts sent, and delegation
chains. This is the primary resource for post-mortem debugging and analysis.

```java
EnsembleOutput output = ensemble.run();

ExecutionTrace trace = output.getTrace();
System.out.println("Run ID:    " + trace.getEnsembleId());
System.out.println("Workflow:  " + trace.getWorkflow());
System.out.println("Duration:  " + trace.getTotalDuration());

// Inspect each task's LLM interactions
for (TaskTrace task : trace.getTaskTraces()) {
    System.out.printf("Task [%s]: %d LLM call(s)%n",
        task.getAgentRole(), task.getLlmInteractions().size());
    for (LlmInteraction interaction : task.getLlmInteractions()) {
        System.out.printf("  Iteration %d: %s, %dms, %d tool call(s)%n",
            interaction.getIterationIndex(),
            interaction.getResponseType(),
            interaction.getLatency().toMillis(),
            interaction.getToolCalls().size());
    }
}
```

### Export to JSON

The trace serializes to pretty-printed JSON with a single method call. All
`Instant` fields are ISO-8601 strings and `Duration` fields are ISO-8601 duration
strings (`PT12.345S`).

```java
// Get as JSON string
String json = output.getTrace().toJson();

// Write to a file
output.getTrace().toJson(Path.of("run-trace.json"));
```

### Automatic export

Register a `traceExporter` on the ensemble to automatically export after every run:

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    // Write each run to traces/<ensembleId>.json
    .traceExporter(new JsonTraceExporter(Path.of("traces/")))
    .build();
```

`JsonTraceExporter` supports two modes:
- **Directory mode** (default): each run writes `{ensembleId}.json` inside the directory
- **File mode**: always overwrites the same file -- useful for single-run pipelines

```java
// Directory mode (each run = new file)
new JsonTraceExporter(Path.of("traces/"))

// File mode (always overwrites)
new JsonTraceExporter(Path.of("run-trace.json"), false)
```

Implement `ExecutionTraceExporter` to send traces to any destination:

```java
Ensemble.builder()
    .traceExporter(trace -> {
        myObservabilityApi.ingest(trace.toJson());
    })
    .build();
```

### Trace structure

The trace is organized as a hierarchy:

```
ExecutionTrace
  schemaVersion, ensembleId, workflow
  startedAt, completedAt, totalDuration
  inputs (template variables)
  agents[] (role, goal, toolNames, allowDelegation)
  taskTraces[]
    agentRole, taskDescription, duration
    prompts (systemPrompt, userPrompt)
    llmInteractions[]
      iterationIndex, latency, inputTokens, outputTokens
      responseType (TOOL_CALLS or FINAL_ANSWER)
      responseText (on FINAL_ANSWER)
      toolCalls[]
        toolName, arguments, result, duration, outcome
    delegations[] (for peer delegation)
    finalOutput, parsedOutput
    metrics (TaskMetrics)
  metrics (ExecutionMetrics)
  totalCostEstimate
  errors[]
```

### Accessing prompt content

The exact prompts sent to the LLM are captured on each `TaskTrace`:

```java
for (TaskTrace task : trace.getTaskTraces()) {
    TaskPrompts prompts = task.getPrompts();
    System.out.println("=== System prompt ===");
    System.out.println(prompts.getSystemPrompt());
    System.out.println("=== User prompt ===");
    System.out.println(prompts.getUserPrompt());
}
```

### Inspecting tool calls

Every tool invocation is recorded with its arguments, result, timing, and outcome:

```java
for (TaskTrace task : trace.getTaskTraces()) {
    for (LlmInteraction iter : task.getLlmInteractions()) {
        for (ToolCallTrace tool : iter.getToolCalls()) {
            System.out.printf("[%s] %s(%s) -> %s [%dms, %s]%n",
                task.getAgentRole(),
                tool.getToolName(),
                tool.getArguments(),
                tool.getResult(),
                tool.getDuration().toMillis(),
                tool.getOutcome());
        }
    }
}
```

Tool call outcomes:
- `SUCCESS` -- tool returned a successful `ToolResult`
- `FAILURE` -- tool returned a failed `ToolResult` (error message begins with `"Error: "`)
- `ERROR` -- tool threw an uncaught exception
- `SKIPPED_MAX_ITERATIONS` -- tool was not executed because the iteration limit was reached

---

## Tool Metrics

In addition to execution metrics, individual tool executions can be instrumented with the
pluggable `ToolMetrics` interface. Every tool that extends `AbstractAgentTool` is
automatically instrumented.

### How tool metrics work

When a tool is executed, `AbstractAgentTool.execute()` automatically records:

- **Success counter** -- incremented when `doExecute()` returns a successful `ToolResult`
- **Failure counter** -- incremented when `doExecute()` returns a failed `ToolResult`
- **Error counter** -- incremented when `doExecute()` throws an uncaught exception
- **Duration timer** -- recorded on every execution regardless of outcome

All measurements are tagged with the **tool name** and the **agent role** that invoked
the tool.

Tools can also record custom measurements using the `metrics()` accessor:

```java
public class InventoryTool extends AbstractAgentTool {
    @Override
    protected ToolResult doExecute(String input) {
        metrics().incrementCounter("inventory.cache.hit", agentRole());
        // ... execute tool
        return ToolResult.success(result);
    }
}
```

### Micrometer integration

Use the `agentensemble-metrics-micrometer` module to export tool metrics to any
Micrometer-compatible registry (Prometheus, Datadog, CloudWatch, etc.):

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

Ensemble ensemble = Ensemble.builder()
    .toolMetrics(new MicrometerToolMetrics(registry))
    .build();
```

### Custom tool metrics implementation

Implement `ToolMetrics` directly for custom backends:

```java
public class MyToolMetrics implements ToolMetrics {
    @Override
    public void incrementSuccess(String toolName, String agentRole) {
        // record success
    }
    @Override
    public void recordDuration(String toolName, String agentRole, Duration duration) {
        // record duration
    }
    // ... other methods
}

Ensemble.builder()
    .toolMetrics(new MyToolMetrics())
    .build();
```
