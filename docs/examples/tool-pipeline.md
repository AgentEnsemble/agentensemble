# Tool Pipeline Example

Source: [`ToolPipelineExample.java`](https://github.com/AgentEnsemble/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/ToolPipelineExample.java)

This example demonstrates `ToolPipeline`: chaining multiple tools into a single compound tool
that the LLM calls once. All steps execute sequentially inside a single tool call, with no LLM
round-trips between steps.

---

## Run It

```bash
export OPENAI_API_KEY=your-api-key
./gradlew :agentensemble-examples:runToolPipeline
```

---

## What It Demonstrates

Two pipelines are built and each is registered as the only tool on a separate task:

### Pipeline 1 -- `extract_and_calculate`

| Step | Tool | What it does |
|------|------|--------------|
| 1 | `JsonParserTool` | Extracts `product.base_price` from a JSON payload (`149.99`) |
| (adapter) | Lambda | Reshapes `"149.99"` into `"149.99 * 1.1"` |
| 2 | `CalculatorTool` | Evaluates `"149.99 * 1.1"` and returns the retail price |

The LLM calls `extract_and_calculate` once with the JSON string. It receives the final numeric
result. No LLM inference occurs between steps 1 and 2.

### Pipeline 2 -- `extract_product_name`

| Step | Tool | What it does |
|------|------|--------------|
| 1 | `JsonParserTool` | Extracts the whole `product` object |
| (adapter) | Lambda | Prepends `"name\n"` to produce the path expression for step 2 |
| 2 | `JsonParserTool` | Extracts the `name` field from the product object |

This pipeline shows that the same tool type can be chained multiple times, and that adapters
reshape the output at each stage.

---

## Code Walk-through

### Build the pipeline

```java
ToolPipeline extractAndCalculate = ToolPipeline.builder()
    .name("extract_and_calculate")
    .description("Given a JSON payload with a 'product.base_price' field, extracts the price "
        + "and returns the price with a 10% markup applied. "
        + "Input: a JSON string containing a product object.")
    .step(new JsonParserTool())
    .adapter(result -> result.getOutput() + " * 1.1")  // (1)
    .step(new CalculatorTool())
    .errorStrategy(PipelineErrorStrategy.FAIL_FAST)    // (2)
    .build();
```

1. The adapter runs after `JsonParserTool` succeeds. It takes the extracted price string
   (e.g., `"149.99"`) and appends the markup formula, producing `"149.99 * 1.1"` for
   `CalculatorTool`.
2. `FAIL_FAST` (the default) stops the pipeline and returns an error to the LLM if any step
   fails. The LLM can adapt based on the error message.

### Register on a task

```java
var priceTask = Task.builder()
    .description("Use the extract_and_calculate tool to compute the retail price...")
    .expectedOutput("The retail price for Widget Pro with a 10% markup applied.")
    .tools(List.of(extractAndCalculate))  // (1)
    .build();
```

1. In v2, tools are registered on the task rather than on an explicit agent. The framework
   synthesizes an agent from the task description and attaches the pipeline to it.

### Run the ensemble

```java
EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(priceTask)
    .build()
    .run();
```

The LLM calls `extract_and_calculate` once with the JSON string. Both steps (`JsonParserTool`
and `CalculatorTool`) execute inside that single call. The LLM receives one tool result and
produces its final answer.

---

## Sample Output

```
============================================================
PIPELINE DEMO
============================================================
Input JSON: {"product": {"name": "Widget Pro", "base_price": 149.99, "category": "hardware"}}

--- extract_and_calculate pipeline result ---
The retail price for Widget Pro with a 10% markup applied is $164.99.
Tool calls: 1 | Duration: PT2.341S

--- extract_product_name pipeline result ---
The product name is Widget Pro.
Tool calls: 1 | Duration: PT1.876S

--- Pipeline structure ---
Pipeline: extract_and_calculate
  Error strategy: FAIL_FAST
  Steps (2):
    [1] json_parser
    [2] calculator
```

Each task made exactly **1 tool call** despite involving 2 underlying tool executions. Without a
pipeline, the LLM would have needed 2 separate tool calls (and 2 LLM inference round-trips).

---

## Key Concepts

### No LLM round-trips between steps

The LLM calls the pipeline once. All internal steps run as ordinary Java method calls within
that single tool invocation. This is the core benefit: deterministic pipelines no longer pay
the per-step LLM inference cost.

### Adapters bridge format mismatches

The adapter lambda `result -> result.getOutput() + " * 1.1"` bridges the mismatch between
`JsonParserTool`'s output format and `CalculatorTool`'s input format. Adapters can contain any
logic and can read `ToolResult.getStructuredOutput()` for typed payloads.

### The pipeline is a single tool

From the LLM's perspective, `extract_and_calculate` is a regular tool with a name, description,
and a single `String` input. The LLM does not know or need to know that two tools are running
inside it.

---

**Full documentation:** [Tool Pipeline Guide](../guides/tool-pipeline.md) | [Design: Tool Pipeline](../design/17-tool-pipeline.md)
