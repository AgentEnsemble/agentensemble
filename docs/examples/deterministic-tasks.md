# Deterministic Tasks

Not every step in an ensemble needs AI reasoning. Sometimes you need to call a REST API,
transform data, or run a tool pipeline without any LLM involvement. **Deterministic tasks**
let you execute any Java function directly as a task step.

---

## When to Use

Use a deterministic task when the output is fully predictable and does not require
language model reasoning:

- Fetching data from a REST API or database
- Parsing and normalizing a response before passing it to an AI task
- Computing a formula or aggregating numbers
- Running a `ToolPipeline` without LLM round-trips between steps
- Formatting or rendering AI-produced output into a final form

---

## Basic Example

```java
Task fetchPrices = Task.builder()
    .description("Fetch current stock prices")
    .expectedOutput("JSON with stock prices")
    .handler(ctx -> ToolResult.success(httpClient.get("https://api.example.com/prices")))
    .build();

// No ChatModel needed for a handler-only ensemble
EnsembleOutput output = Ensemble.builder()
    .task(fetchPrices)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run();

System.out.println(output.getRaw());  // {"AAPL": 175.0, "MSFT": 320.0}
```

The `handler` receives a `TaskHandlerContext` and must return a `ToolResult`:

- `ToolResult.success(String output)` -- normal completion
- `ToolResult.failure(String error)` -- signals task failure (throws `TaskExecutionException`)

---

## Accessing Prior Task Outputs

The `TaskHandlerContext` provides `contextOutputs()` -- the outputs of all tasks declared
in `Task.context()`:

```java
Task analyze = Task.builder()
    .description("Analyze the stock prices")
    .expectedOutput("Investment summary")
    .chatLanguageModel(model)
    .build();

// Deterministic task that transforms the AI output
Task formatReport = Task.builder()
    .description("Format the analysis as an HTML report")
    .expectedOutput("HTML report")
    .context(List.of(analyze))
    .handler(ctx -> {
        String aiOutput = ctx.contextOutputs().get(0).getRaw();
        String html = "<html><body>" + aiOutput + "</body></html>";
        return ToolResult.success(html);
    })
    .build();
```

---

## Wrapping an Existing Tool

Pass any `AgentTool` directly to the `handler()` builder method:

```java
// Input = task description (no context) or last context output (with context)
Task fetch = Task.builder()
    .description("https://api.example.com/prices")
    .expectedOutput("HTTP response")
    .handler(httpTool)
    .build();
```

---

## ToolPipeline as Handler

`ToolPipeline` implements `AgentTool`, so it works with the same overload:

```java
ToolPipeline extractAndCalculate = ToolPipeline.builder()
    .name("extract_and_calculate")
    .description("Extract price and apply discount")
    .step(new JsonParserTool())
    .adapter(result -> result.getOutput() + " * 0.90")
    .step(new CalculatorTool())
    .build();

Task computeDiscounted = Task.builder()
    .description(jsonPayload + "\nbase_price")
    .expectedOutput("Discounted price")
    .handler(extractAndCalculate)    // executed directly, no LLM
    .build();
```

This is more efficient than running the same pipeline in the LLM tool-calling loop because
no LLM call is made at all.

---

## Mixed Ensemble: Deterministic + AI

```java
// Step 1: Deterministic -- fetch and normalize data (no LLM)
Task fetchData = Task.builder()
    .description("Fetch product data from the catalog API")
    .expectedOutput("Normalized product data")
    .handler(ctx -> {
        String data = catalogApi.getProduct("WIDGET-001");
        return ToolResult.success(normalize(data));
    })
    .build();

// Step 2: AI -- analyze the normalized data
Task analyze = Task.builder()
    .description("Write a 2-sentence marketing summary for the product")
    .expectedOutput("Marketing summary")
    .chatLanguageModel(model)
    .context(List.of(fetchData))
    .build();

// Step 3: Deterministic -- format the AI output as HTML (no LLM)
Task render = Task.builder()
    .description("Render the marketing summary as HTML")
    .expectedOutput("HTML snippet")
    .context(List.of(analyze))
    .handler(ctx -> ToolResult.success(
        "<p>" + ctx.contextOutputs().get(0).getRaw() + "</p>"))
    .build();

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .task(fetchData)
    .task(analyze)
    .task(render)
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run();
```

---

## Structured Output

If the task has `outputType` set, the handler can provide a pre-typed Java object via
`ToolResult.success(text, typedValue)` to skip JSON deserialization:

```java
record PriceReport(String symbol, double price) {}

Task fetch = Task.builder()
    .description("Fetch AAPL price")
    .expectedOutput("Price report")
    .outputType(PriceReport.class)
    .handler(ctx -> {
        PriceReport report = priceApi.getPrice("AAPL");
        return ToolResult.success(report.toString(), report);
    })
    .build();

EnsembleOutput output = ...;
PriceReport report = output.getOutput(fetch).getParsedOutput(PriceReport.class);
```

---

## Guardrails and Review Gates

Deterministic tasks support the same lifecycle features as AI-backed tasks:

```java
Task fetchData = Task.builder()
    .description("Fetch customer data")
    .expectedOutput("Customer JSON")
    .inputGuardrails(List.of(input -> {
        // reject if description contains sensitive terms
        return GuardrailResult.success();
    }))
    .outputGuardrails(List.of(out -> {
        // reject if output contains PII
        return GuardrailResult.success();
    }))
    .handler(ctx -> ToolResult.success(customerApi.getData()))
    .build();
```

---

## TaskOutput Metadata

Deterministic tasks appear in `EnsembleOutput` with:

- `agentRole` = `"(deterministic)"` (not an AI agent)
- `toolCallCount` = `0`
- `metrics` = `TaskMetrics.EMPTY` (no token usage)

```java
for (TaskOutput taskOutput : output.getTaskOutputs()) {
    System.out.printf("[%s] %s: %s%n",
        taskOutput.getAgentRole(),       // "(deterministic)" or agent role
        taskOutput.getTaskDescription(),
        taskOutput.getRaw());
}
```

---

## Constraints

- **Hierarchical workflow**: Not supported. Use `SEQUENTIAL` or `PARALLEL` when mixing
  deterministic and AI-backed tasks.

- **Mutually exclusive with LLM fields**: `agent`, `chatLanguageModel`,
  `streamingChatLanguageModel`, `tools`, `maxIterations`, and `rateLimit` cannot be
  set alongside `handler` (rejected at build time with `ValidationException`).

---

## Runnable Example

```bash
./gradlew :agentensemble-examples:runDeterministicTask
```

Source: [`DeterministicTaskExample.java`](https://github.com/AgentEnsemble/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/DeterministicTaskExample.java)

---

## Deterministic-Only Pipeline (no AI at all)

When **every** task in the ensemble has a handler, no `ChatModel` is needed at any level.
Use the `Ensemble.run(Task...)` zero-ceremony factory for the most concise form:

```java
Task fetchTask = Task.builder()
    .description("Fetch product data from API")
    .expectedOutput("JSON product data")
    .handler(ctx -> ToolResult.success(apiClient.fetchProducts()))
    .build();

Task parseTask = Task.builder()
    .description("Parse JSON into structured records")
    .expectedOutput("Parsed product list")
    .context(List.of(fetchTask))
    .handler(ctx -> {
        String json = ctx.contextOutputs().get(0).getRaw();
        return ToolResult.success(jsonParser.parse(json));
    })
    .build();

Task storeTask = Task.builder()
    .description("Write records to data warehouse")
    .expectedOutput("Row count written")
    .context(List.of(parseTask))
    .handler(ctx -> {
        String data = ctx.contextOutputs().get(0).getRaw();
        int rows = warehouse.insert(data);
        return ToolResult.success(rows + " rows inserted");
    })
    .build();

// No ChatModel required -- all tasks are deterministic
EnsembleOutput output = Ensemble.run(fetchTask, parseTask, storeTask);
System.out.println(output.getRaw()); // "1234 rows inserted"
```

Parallel fan-out (three independent service calls, then merge) is inferred automatically
from `context()` dependencies -- no explicit `workflow(Workflow.PARALLEL)` needed:

```java
Task serviceA = Task.builder().description("Fetch from A").handler(ctx -> ToolResult.success(a.fetch())).build();
Task serviceB = Task.builder().description("Fetch from B").handler(ctx -> ToolResult.success(b.fetch())).build();
Task merge    = Task.builder()
    .description("Merge A and B")
    .context(List.of(serviceA, serviceB))
    .handler(ctx -> {
        String a = ctx.contextOutputs().get(0).getRaw();
        String b = ctx.contextOutputs().get(1).getRaw();
        return ToolResult.success(merge(a, b));
    })
    .build();

// serviceA and serviceB run concurrently; merge waits for both
EnsembleOutput output = Ensemble.builder().task(serviceA).task(serviceB).task(merge).build().run();
```

See the [Deterministic Orchestration guide](../guides/deterministic-orchestration.md) for
the full reference including phases, callbacks, guardrails, and failure handling.

## Runnable Example (no-API-key required)

```bash
./gradlew :agentensemble-examples:runDeterministicOnlyPipeline
```

Source: [`DeterministicOnlyPipelineExample.java`](https://github.com/AgentEnsemble/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/DeterministicOnlyPipelineExample.java)
