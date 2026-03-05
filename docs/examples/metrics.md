# Execution Metrics and Trace Export

This example demonstrates how to access execution metrics, use cost estimation, and
export a complete execution trace to JSON.

## Basic metrics access

```java
Agent researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Research the given topic thoroughly")
    .llm(openAiModel)
    .build();

Task researchTask = Task.builder()
    .description("Research the latest advances in AI agent frameworks")
    .expectedOutput("A comprehensive research report")
    .agent(researcher)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run();

// Per-run metrics
ExecutionMetrics metrics = output.getMetrics();
System.out.println("Total tokens:   " + metrics.getTotalTokens());
System.out.println("LLM latency:    " + metrics.getTotalLlmLatency());
System.out.println("Tool time:      " + metrics.getTotalToolExecutionTime());
System.out.println("Total LLM calls:" + metrics.getTotalLlmCallCount());

// Per-task metrics
for (TaskOutput task : output.getTaskOutputs()) {
    TaskMetrics tm = task.getMetrics();
    System.out.printf("[%s] in=%d out=%d latency=%s tools=%s%n",
        task.getAgentRole(),
        tm.getInputTokens(),
        tm.getOutputTokens(),
        tm.getLlmLatency(),
        tm.getToolExecutionTime());
}
```

## Cost estimation

Supply per-token rates and the framework computes cost estimates for each task and the
total run.

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .costConfiguration(CostConfiguration.builder()
        .inputTokenRate(new BigDecimal("0.0000025"))   // $2.50 per million input tokens
        .outputTokenRate(new BigDecimal("0.0000100"))  // $10.00 per million output tokens
        .currency("USD")
        .build())
    .build();

EnsembleOutput output = ensemble.run();

CostEstimate totalCost = output.getMetrics().getTotalCostEstimate();
if (totalCost != null) {
    System.out.printf("Total cost: $%.6f%n", totalCost.getTotalCost());
    System.out.printf("  Input:  $%.6f%n", totalCost.getInputCost());
    System.out.printf("  Output: $%.6f%n", totalCost.getOutputCost());
}

// Per-task cost
for (TaskOutput task : output.getTaskOutputs()) {
    CostEstimate taskCost = task.getMetrics().getCostEstimate();
    if (taskCost != null) {
        System.out.printf("[%s] $%.6f%n", task.getAgentRole(), taskCost.getTotalCost());
    }
}
```

Cost estimation is skipped when the LLM provider does not return token usage metadata.
In that case, `getCostEstimate()` returns `null`.

## Execution trace to JSON

Export a full structured trace of the run for analysis or storage:

```java
EnsembleOutput output = ensemble.run();

// Write trace to a file
output.getTrace().toJson(Path.of("run-trace.json"));

// Or capture as a string
String json = output.getTrace().toJson();
```

Sample JSON output (abbreviated):
```json
{
  "schemaVersion" : "1.0",
  "ensembleId" : "a1b2c3d4-...",
  "workflow" : "SEQUENTIAL",
  "startedAt" : "2026-03-05T09:00:00Z",
  "completedAt" : "2026-03-05T09:00:12.345Z",
  "totalDuration" : "PT12.345S",
  "taskTraces" : [ {
    "agentRole" : "Senior Research Analyst",
    "taskDescription" : "Research the latest advances in AI agent frameworks",
    "duration" : "PT12.3S",
    "prompts" : {
      "systemPrompt" : "You are a Senior Research Analyst...",
      "userPrompt" : "Research the latest advances..."
    },
    "llmInteractions" : [ {
      "iterationIndex" : 0,
      "latency" : "PT3.2S",
      "inputTokens" : 800,
      "outputTokens" : 450,
      "responseType" : "FINAL_ANSWER",
      "responseText" : "Here is my research...",
      "toolCalls" : [ ]
    } ],
    "finalOutput" : "Here is my research...",
    "metrics" : {
      "inputTokens" : 800,
      "outputTokens" : 450,
      "totalTokens" : 1250,
      "llmLatency" : "PT3.2S",
      "llmCallCount" : 1
    }
  } ],
  "metrics" : {
    "totalInputTokens" : 800,
    "totalOutputTokens" : 450,
    "totalTokens" : 1250
  }
}
```

## Automatic trace export

Use `JsonTraceExporter` to write each run automatically:

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .traceExporter(new JsonTraceExporter(Path.of("traces/")))
    .build();

// Each run writes traces/<ensembleId>.json
ensemble.run();
ensemble.run(Map.of("topic", "quantum computing"));  // writes another file
```

## Custom trace exporter

Implement `ExecutionTraceExporter` for any destination:

```java
Ensemble.builder()
    .traceExporter(trace -> {
        // Send to your own storage
        myDatabase.insert("traces", trace.getEnsembleId(), trace.toJson());
    })
    .build();
```

## Inspecting individual tool calls

The trace captures every tool invocation with its arguments and result:

```java
ExecutionTrace trace = output.getTrace();
for (TaskTrace task : trace.getTaskTraces()) {
    for (LlmInteraction iteration : task.getLlmInteractions()) {
        System.out.printf("Iteration %d (%s):%n",
            iteration.getIterationIndex(),
            iteration.getResponseType());
        for (ToolCallTrace tool : iteration.getToolCalls()) {
            System.out.printf("  Tool: %s%n",   tool.getToolName());
            System.out.printf("  Args: %s%n",   tool.getArguments());
            System.out.printf("  Result: %s%n", tool.getResult());
            System.out.printf("  Time: %dms%n", tool.getDuration().toMillis());
            System.out.printf("  Outcome: %s%n", tool.getOutcome());
        }
        if (iteration.getResponseType() == LlmResponseType.FINAL_ANSWER) {
            System.out.println("  Final: " + iteration.getResponseText());
        }
    }
}
```

## Combining metrics with callbacks

Use both metrics and event listeners together for real-time monitoring:

```java
Ensemble ensemble = Ensemble.builder()
    .agent(researcher)
    .task(researchTask)
    .onTaskComplete(event -> {
        TaskMetrics tm = event.getTaskOutput().getMetrics();
        log.info("Task {} used {} tokens", event.agentRole(), tm.getTotalTokens());
    })
    .onToolCall(event -> {
        log.debug("Tool {} took {}ms", event.toolName(), event.duration().toMillis());
    })
    .traceExporter(new JsonTraceExporter(Path.of("traces/")))
    .build();
```
