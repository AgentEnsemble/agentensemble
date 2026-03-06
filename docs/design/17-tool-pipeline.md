# 17 - Tool Pipeline

This document specifies the `ToolPipeline` mechanism: a Unix-pipe-style composition of multiple
`AgentTool` instances into a single compound tool that the LLM calls once.

## Motivation

Tool chaining in the ReAct loop is **LLM-mediated**: each step in a `search -> filter -> format`
chain requires a full LLM inference round-trip. For deterministic, data-transformation pipelines
the LLM adds no reasoning value but does add latency and token cost.

`ToolPipeline` eliminates those intermediate round-trips by executing all steps inside a single
`AgentTool.execute(String)` call. The LLM sees one atomic tool and calls it once; all steps run
without further LLM involvement.

## Classes

```
AgentTool (interface)
  AbstractAgentTool (abstract)
    ToolPipeline (final)           -- the pipeline

PipelineErrorStrategy (enum)      -- FAIL_FAST | CONTINUE_ON_FAILURE
```

`ToolPipeline` lives in `net.agentensemble.tool` alongside the rest of the tool package.
Being in the same package lets it override the package-private `setContext()` on
`AbstractAgentTool` to propagate context injection to nested steps.

## Data Handoff

The uniform `String -> ToolResult` contract on `AgentTool` is the natural pipe interface:

```
step 1 execute(input)  -> ToolResult
        |
        | getOutput() (or adapter function)
        v
step 2 execute(adaptedInput) -> ToolResult
        |
        | getOutput() (or adapter function)
        v
       ...
        |
        v
step N execute(...) -> ToolResult  <- returned to LLM
```

By default `ToolResult.getOutput()` is forwarded verbatim. When the output needs to be reshaped
before the next step (for example, to inject a path expression prefix for `JsonParserTool`), an
**output adapter** (`Function<ToolResult, String>`) can be attached to any step via the builder.

Adapters have access to the full `ToolResult`, including `getStructuredOutput()`, so typed
payloads can be unpacked and formatted.

### Adapter Invocation Rules

- The adapter attached to step N is called only when step N **succeeds** (`isSuccess() == true`).
- When step N fails and the strategy is `CONTINUE_ON_FAILURE`, the error message is forwarded
  directly and the adapter is **not** called.
- The adapter on the **last** step is never called (no next step to receive its output).

## Error Strategy

| Strategy | Behaviour on step failure |
|---|---|
| `FAIL_FAST` (default) | Stop immediately; return the failed `ToolResult` to the LLM. Subsequent steps are skipped. |
| `CONTINUE_ON_FAILURE` | Forward `ToolResult.getErrorMessage()` (or empty string) as the next step's input. Continue to the final step. Return the last step's result. |

`FAIL_FAST` is consistent with how individual tools behave: a single failed tool returns an
error to the LLM without running further tools.

`CONTINUE_ON_FAILURE` is for resilient pipelines where downstream steps can handle or recover
from upstream errors.

## ToolContext Propagation

When the framework injects a `ToolContext` into the pipeline via `ToolContextInjector`, the
pipeline's overridden `setContext()` iterates all steps and calls `setContext()` on any step
that is an `AbstractAgentTool`. This ensures:

- Each step gets the same `ToolMetrics`, `Executor`, and `Logger` as the pipeline.
- Approval-gate steps receive the ensemble's `ReviewHandler`.

Plain `AgentTool` steps (not `AbstractAgentTool`) are unaffected: they receive no context injection
and rely on their own internal state.

## Metrics

| Level | What is recorded |
|---|---|
| Pipeline | Aggregate: single success/failure/error count + total duration for the whole pipeline (via `AbstractAgentTool.execute()`) |
| Each AbstractAgentTool step | Per-step: individual success/failure/error counts + duration (via their own `AbstractAgentTool.execute()`) |

## LLM Integration

`ToolPipeline extends AbstractAgentTool implements AgentTool`. The existing
`LangChain4jToolAdapter` adapts any `AgentTool` into a `ToolSpecification` with a single
`"input"` string parameter. No special handling is required in `ToolResolver` or
`LangChain4jToolAdapter`. The pipeline is registered exactly like any other tool.

## Execution Flow

```
LLM tool call: pipeline("initial input")
  |
  v
AbstractAgentTool.execute("initial input")
  [timing start]
  |
  v
ToolPipeline.doExecute("initial input")
  |
  +---> step1.execute("initial input")       -> ToolResult{output="A"}
  |        [metrics recorded for step1]
  |
  +---> adapter1(ToolResult{output="A"})     -> "adapted_A"
  |        [only if step1 succeeded]
  |
  +---> step2.execute("adapted_A")           -> ToolResult{output="B"}
  |        [metrics recorded for step2]
  |
  +---> step3.execute("B")                   -> ToolResult{output="final"}
  |        [metrics recorded for step3]
  |        [last step: adapter not called]
  |
  v
  returns ToolResult{output="final"}
  [timing stop]
  [pipeline aggregate metrics recorded]
  |
  v
LLM receives: "final" as tool result
```

## Builder API

```java
// Minimal factory -- auto-generates name and description
ToolPipeline pipeline = ToolPipeline.of(
    new JsonParserTool(),
    new CalculatorTool()
);
// name: "json_parser_then_calculator"
// description: "Pipeline: json_parser -> calculator"

// Named factory
ToolPipeline pipeline = ToolPipeline.of(
    "extract_and_calculate",
    "Extracts a numeric field and applies a formula",
    new JsonParserTool(),
    new CalculatorTool()
);

// Full builder
ToolPipeline pipeline = ToolPipeline.builder()
    .name("search_and_save")
    .description("Search for information and save the result to disk")
    .step(new WebSearchTool(provider))
    .adapter(result -> "results[0]\n" + result.getOutput())
    .step(new JsonParserTool())
    .step(FileWriteTool.of(outputPath))
    .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
    .build();
```

## Design Decisions

### Why `AbstractAgentTool` (not just `AgentTool`)

Extending `AbstractAgentTool` gives the pipeline automatic metrics, exception safety, structured
logging, and approval-gate capability for free. The pipeline itself is instrumented without any
extra code. The `execute()` method is `final` in `AbstractAgentTool`, so framework instrumentation
cannot be bypassed.

### Why package-private `setContext()` override

The `setContext()` method is intentionally package-private in `AbstractAgentTool` to prevent
user code from calling it. Placing `ToolPipeline` in `net.agentensemble.tool` gives it access to
this method to propagate context to nested steps, without exposing the method to application code.

### Why not a recursive/tree pipeline

Issue #74 explicitly identified fan-out/fan-in as a future extension. The linear model covers
the common case (`search -> filter -> write`) cleanly. Parallel branches can be addressed in a
follow-up by composing pipelines with the existing parallel execution infrastructure.

### Why string-based handoff (not typed)

The `AgentTool` interface uses `String` input universally. A typed contract would require
changing the interface and adding generics. String-based handoff is consistent with the existing
tool model. Adapters provide the escape hatch for typed reshaping when needed.
