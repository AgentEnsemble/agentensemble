# Tool Pipeline

`ToolPipeline` chains multiple tools together into a single compound tool that the LLM calls
once. All steps execute sequentially **without LLM round-trips between them**, eliminating the
token cost and latency of letting the LLM mediate each step in the ReAct loop.

---

## The Problem It Solves

In a standard ReAct loop, tool chaining looks like this:

```
LLM -> calls search_tool     -> receives results
LLM -> calls filter_tool     -> receives filtered output   (1 extra LLM round-trip)
LLM -> calls format_tool     -> receives formatted output  (1 extra LLM round-trip)
LLM -> produces final answer
```

Every step requires full LLM inference. For deterministic data transformations the LLM adds no
reasoning value but does add latency and tokens.

With `ToolPipeline`:

```
LLM -> calls search_then_filter_then_format  -> receives final output  (0 extra round-trips)
LLM -> produces final answer
```

---

## Quick Start

=== "Simple (auto-generated name)"

    ```java
    import net.agentensemble.tool.ToolPipeline;
    import net.agentensemble.tools.web.search.WebSearchTool;
    import net.agentensemble.tools.json.JsonParserTool;
    import net.agentensemble.tools.io.FileWriteTool;

    ToolPipeline pipeline = ToolPipeline.of(
        new WebSearchTool(provider),
        new JsonParserTool(),
        FileWriteTool.of(outputPath)
    );
    // name: "web_search_then_json_parser_then_file_write"
    ```

=== "Named (explicit name and description)"

    ```java
    ToolPipeline pipeline = ToolPipeline.of(
        "search_and_save",
        "Search for information, extract the top result, and save it to disk",
        new WebSearchTool(provider),
        new JsonParserTool(),
        FileWriteTool.of(outputPath)
    );
    ```

Register it on a task just like any other tool:

```java
var task = Task.builder()
    .description("Research AI trends and save the top result to disk")
    .expectedOutput("Confirmation that the result was saved")
    .tools(List.of(pipeline))
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(task)
    .build()
    .run();
```

---

## How Data Flows Between Steps

By default, `ToolResult.getOutput()` (a plain `String`) from step N is passed as the `input`
to step N+1.

```
initial input  ---> [step 1]  --output--> [step 2]  --output--> [step 3]  --output--> LLM
```

When you need to reshape or reformat the output before it reaches the next step, attach an
**adapter** using the builder:

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("extract_and_calculate")
    .description("Extract a numeric field from JSON and apply a formula to it")
    .step(new JsonParserTool())
    .adapter(result -> result.getOutput() + " * 1.1")   // (1)
    .step(new CalculatorTool())
    .build();
```

1. The adapter transforms the `JsonParserTool` output (e.g., `"149.99"`) into a calculator
   expression (`"149.99 * 1.1"`) before passing it to `CalculatorTool`.

Adapters have full access to the `ToolResult`, including `getStructuredOutput()` for typed
payloads:

```java
.adapter(result -> {
    MyRecord payload = result.getStructuredOutput(MyRecord.class);
    return payload != null ? String.valueOf(payload.value()) : result.getOutput();
})
```

---

## Error Strategies

### `FAIL_FAST` (default)

Stop the pipeline on the first failed step and return that failure to the LLM immediately.
Subsequent steps are never executed.

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("my_pipeline")
    .description("desc")
    .step(stepA)
    .step(stepB)   // if stepA fails, stepB is never called
    .step(stepC)
    .errorStrategy(PipelineErrorStrategy.FAIL_FAST)  // default, may be omitted
    .build();
```

### `CONTINUE_ON_FAILURE`

Continue executing subsequent steps even when an intermediate step fails. The failed step's
error message is forwarded as input to the next step. The final result of the pipeline is the
result of the last step.

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("resilient_pipeline")
    .description("Continues even when a step fails")
    .step(stepA)
    .step(stepB)   // stepB receives stepA's error message if stepA fails
    .step(stepC)
    .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
    .build();
```

Use `CONTINUE_ON_FAILURE` when downstream steps can handle or recover from upstream failures,
or when you always want to produce an output regardless of partial failures.

---

## Full Builder Reference

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("my_pipeline")                              // required: tool name shown to LLM
    .description("What this pipeline does")           // required: tool description shown to LLM
    .step(new WebSearchTool(provider))                // step 1
    .adapter(result -> "title\n" + result.getOutput()) // adapter: reshape step 1 output
    .step(new JsonParserTool())                       // step 2
    .step(FileWriteTool.of(outputPath))               // step 3 (no adapter -- raw output passed)
    .errorStrategy(PipelineErrorStrategy.FAIL_FAST)   // default
    .build();
```

| Method | Required | Description |
|--------|----------|-------------|
| `name(String)` | Yes | Tool name exposed to the LLM. Must be unique within the task's tool list. |
| `description(String)` | Yes | Tool description shown to the LLM to help it select this tool. |
| `step(AgentTool)` | At least one | Add a step. Steps execute in registration order. |
| `adapter(Function<ToolResult, String>)` | No | Transform the output of the preceding step before passing it to the next step. Called only on success. |
| `errorStrategy(PipelineErrorStrategy)` | No | `FAIL_FAST` (default) or `CONTINUE_ON_FAILURE`. |

---

## Factory Methods

For simple cases without adapters or custom error strategies:

```java
// Auto-generated name from step names joined with "_then_"
ToolPipeline pipeline = ToolPipeline.of(stepA, stepB, stepC);
// name: "step_a_then_step_b_then_step_c"
// description: "Pipeline: step_a -> step_b -> step_c"

// Explicit name and description
ToolPipeline pipeline = ToolPipeline.of(
    "search_and_parse",
    "Search for information and extract the top result title",
    new WebSearchTool(provider),
    new JsonParserTool()
);
```

---

## Inspecting a Pipeline

```java
// Get the ordered list of steps
List<AgentTool> steps = pipeline.getSteps();
System.out.println("Pipeline has " + steps.size() + " steps:");
for (int i = 0; i < steps.size(); i++) {
    System.out.printf("  [%d] %s%n", i + 1, steps.get(i).name());
}

// Get the configured error strategy
PipelineErrorStrategy strategy = pipeline.getErrorStrategy();
System.out.println("Error strategy: " + strategy);  // FAIL_FAST or CONTINUE_ON_FAILURE
```

---

## Metrics

Each step that extends `AbstractAgentTool` records its own metrics (timing, success/failure
counts) as it normally would. The pipeline itself also records an **aggregate** timing and
success/failure count for the whole chain via the inherited `AbstractAgentTool` instrumentation.

When Micrometer metrics are configured on the ensemble, you will see per-step and per-pipeline
metrics in your metrics backend.

---

## Approval Gates Within Pipelines

Steps inside a pipeline that extend `AbstractAgentTool` and call `requestApproval()` will pause
for human review mid-pipeline, exactly as if they were standalone tools. The pipeline propagates
the ensemble's `ReviewHandler` to all nested steps automatically.

```java
ToolPipeline pipeline = ToolPipeline.of(
    new JsonParserTool(),
    FileWriteTool.builder(outputPath)     // requires approval before writing
        .requireApproval(true)
        .build()
);

Ensemble.builder()
    .task(task)
    .reviewHandler(ReviewHandler.console())   // reviewer sees the write request mid-pipeline
    .build()
    .run();
```

---

## Common Patterns

### JSON extraction and arithmetic

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("extract_and_calculate")
    .description("Extract a numeric field from JSON and apply a formula to it. "
        + "Input format: path on first line, JSON on remaining lines.")
    .step(new JsonParserTool())
    .adapter(result -> result.getOutput() + " * 1.1")
    .step(new CalculatorTool())
    .build();
```

### Web search, extract, and save

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("research_and_save")
    .description("Search the web for a query, extract the first result title, "
        + "and write it to a file. Input: a search query.")
    .step(new WebSearchTool(provider))
    .adapter(result -> "results[0].title\n" + result.getOutput())
    .step(new JsonParserTool())
    .step(FileWriteTool.of(outputPath))
    .build();
```

### Chaining the same tool type

```java
ToolPipeline pipeline = ToolPipeline.builder()
    .name("deep_extract")
    .description("Extracts a deeply nested field from JSON in two steps. "
        + "Input: 'outer_field' on first line, outer JSON on remaining lines.")
    .step(new JsonParserTool())                          // extracts outer object
    .adapter(result -> "nested_field\n" + result.getOutput())
    .step(new JsonParserTool())                          // extracts nested field
    .build();
```

---

## Nesting Pipelines

A `ToolPipeline` implements `AgentTool`, so it can be used as a step inside another pipeline:

```java
ToolPipeline innerPipeline = ToolPipeline.of("step_a", "desc", toolA, toolB);
ToolPipeline outerPipeline = ToolPipeline.of("outer", "desc", innerPipeline, toolC);
```

---

## When to Use ToolPipeline vs. Separate Tools

| Use `ToolPipeline` when... | Use separate tools when... |
|----------------------------|---------------------------|
| Steps are deterministic and order-locked -- the LLM should not skip or reorder them | The LLM needs to reason between steps (e.g., decide which tool to call next based on intermediate results) |
| You want to reduce token costs for data transformation chains | The pipeline structure should be flexible and LLM-directed |
| The full chain should appear as one operation to the LLM | Intermediate results are useful for the LLM to see and reason about |

**Full documentation:** [Design: Tool Pipeline](../design/17-tool-pipeline.md) | [Built-in Tools](built-in-tools.md)
