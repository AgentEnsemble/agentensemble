# Active Context

## Current Work Focus

Issue #42 (execution metrics, token tracking, cost estimation, and execution trace) has
been implemented on `feature/42-execution-metrics` (1 commit: `d01ea3e`):

- `d01ea3e` feat(#42): execution metrics, token tracking, cost estimation, and execution trace

The feature branch is ready for PR. All tests pass; full
`./gradlew build :agentensemble-core:javadoc --continue` is green.

## Recent Changes

### Issue #42 -- Execution Metrics and Observability

**New packages:**

**`net.agentensemble.metrics`** -- Flat, immutable value objects:
- `TaskMetrics` (`@Value @Builder`) -- per-task token counts (input, output, total with -1
  unknown convention), LLM latency, tool execution time, prompt build time, memory retrieval
  time, LLM call count, tool call count, delegation count, memory operation counts, optional
  CostEstimate
- `ExecutionMetrics` (`@Value @Builder`) -- aggregated run-level metrics; static
  `from(List<TaskOutput>)` factory with -1-propagation for unknown tokens
- `MemoryOperationCounts` (`@Value @Builder`) -- STM writes, LTM stores/retrievals, entity lookups;
  `add()` for aggregation; `ZERO` constant
- `CostConfiguration` (`@Value @Builder`) -- `inputTokenRate`, `outputTokenRate`, `currency`;
  `estimate(long, long)` returns `null` when tokens are -1
- `CostEstimate` (`@Value @Builder`) -- `inputCost`, `outputCost`, `totalCost`; `add()` method;
  `EMPTY` constant

**`net.agentensemble.trace`** -- Hierarchical call trace:
- `ExecutionTrace` (`@Value @Builder(toBuilder=true)`) -- top-level trace; `schemaVersion="1.0"`;
  `ensembleId`, `workflow`, `startedAt`/`completedAt`/`totalDuration`, `inputs`, `agents`,
  `taskTraces`, `metrics`, `totalCostEstimate`, `errors`, `metadata`; `toJson()` / `toJson(Path)`
  via Jackson+JavaTimeModule; `export(ExecutionTraceExporter)`
- `TaskTrace` -- complete per-task trace with prompts, `llmInteractions`, `delegations`,
  `finalOutput`, `parsedOutput`, `metrics`, `metadata`
- `LlmInteraction` -- one ReAct iteration: `iterationIndex`, `startedAt`/`completedAt`/`latency`,
  `inputTokens`/`outputTokens` (-1 if unknown), `responseType` (TOOL_CALLS/FINAL_ANSWER),
  `responseText`, `toolCalls`
- `ToolCallTrace` -- one tool invocation: `toolName`, `arguments`, `result`, `structuredOutput`,
  timing, `outcome` (SUCCESS/FAILURE/ERROR/SKIPPED_MAX_ITERATIONS), `metadata`
- `DelegationTrace` -- delegation record: `delegatorRole`, `workerRole`, `taskDescription`,
  timing, `depth`, `result`, `succeeded`, `workerTrace` (nested TaskTrace for peer delegation)
- `AgentSummary`, `TaskPrompts`, `ErrorTrace`, `LlmResponseType`, `ToolCallOutcome`

**`net.agentensemble.trace.export`**:
- `ExecutionTraceExporter` -- `@FunctionalInterface`: `void export(ExecutionTrace trace)`
- `JsonTraceExporter` -- directory mode (per-run `{ensembleId}.json`) or file mode

**`net.agentensemble.trace.internal`**:
- `TaskTraceAccumulator` -- mutable per-task collector; `beginLlmCall()`, `endLlmCall()`,
  `addToolCallToCurrentIteration()`, `finalizeIteration()`, `addDelegation()`, memory counters;
  `buildTrace()` / `buildMetrics()` freeze to immutable objects

**Modified classes:**

- `TaskOutput`: added `metrics` (`TaskMetrics`, default `EMPTY`) and `trace` (`TaskTrace`, nullable)
- `EnsembleOutput`: custom builder with auto-computed `ExecutionMetrics.from(taskOutputs)`;
  added `trace` (`ExecutionTrace`, nullable). Changed from `@Builder @Value` to `@Value` with
  manual fluent builder.
- `AgentExecutor`: creates `TaskTraceAccumulator` per execution; times prompt building,
  wraps each `LLM.chat()` with `beginLlmCall()`/`endLlmCall()`; builds `ToolCallTrace` per tool
  invocation; wires `accumulator::addDelegation` into `AgentDelegationTool` via 3-arg constructor
- `AgentDelegationTool`: new 3-arg constructor accepts `Consumer<DelegationTrace>`;
  builds `DelegationTrace` (with nested worker `TaskTrace`) after successful delegation
- `ExecutionContext`: added `costConfiguration()` (nullable `CostConfiguration`)
- `Ensemble`: added `costConfiguration` and `traceExporter` builder fields; `runWithInputs()`
  passes costConfig to `ExecutionContext`, assembles `ExecutionTrace`, and calls exporter

**Dependencies:** `jackson-datatype-jsr310` added to `agentensemble-core`

**Tests added (all pass, coverage >= 90%):**
- `TaskMetricsTest`, `ExecutionMetricsTest`, `MemoryOperationCountsTest`,
  `CostConfigurationTest`, `TaskTraceAccumulatorTest`, `AgentExecutorMetricsTest`,
  `ExecutionTraceTest`, `EnsembleOutputTest`

**Docs updated:** `docs/guides/metrics.md` (major rewrite), `docs/examples/metrics.md` (new),
`docs/reference/ensemble-configuration.md` (new fields + output methods), `README.md`
(Metrics section), `docs/design/01-overview.md`, `mkdocs.yml`

## Next Steps

- Open PR for `feature/42-execution-metrics` targeting `main`
- After merge, close GitHub issue #42
