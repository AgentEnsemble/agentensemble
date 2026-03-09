# Ensemble Configuration Reference

All fields available on `Ensemble.builder()`.

## Static Factory

| Method | Description |
|---|---|
| `Ensemble.run(ChatModel model, Task... tasks)` | Zero-ceremony: create and run a sequential ensemble with the given LLM. Agents are synthesized from task descriptions. |

---

## Builder Fields

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `tasks` | `List<Task>` | Yes | -- | All tasks to execute. Add with `.task(t)` (singular) or `.tasks(list)`. |
| `chatLanguageModel` | `ChatModel` | No | `null` | Default LLM for all tasks without an explicit agent or task-level LLM. Required when any task lacks an explicit agent and does not set its own `chatLanguageModel`. Also used as the Manager LLM in hierarchical workflow if `managerLlm` is not set. |
| `rateLimit` | `RateLimit` | No | `null` | Ensemble-level request rate limit applied to `chatLanguageModel`. When set, all synthesized agents that inherit the ensemble model share one token bucket, capping requests per time window across the entire run. The model is wrapped with `RateLimitedChatModel` once per `run()` call. Tasks with their own `chatLanguageModel` or `rateLimit` are unaffected. See [Rate Limiting guide](../guides/rate-limiting.md). |
| `agentSynthesizer` | `AgentSynthesizer` | No | `AgentSynthesizer.template()` | Strategy for synthesizing agents for agentless tasks. `AgentSynthesizer.template()` (default) derives role, goal, and backstory from the task description deterministically. `AgentSynthesizer.llmBased()` invokes the LLM once per agentless task for a higher-quality persona. |
| `inputs` | `Map<String, String>` | No | `{}` | Template variable values applied to all task descriptions and expected outputs at run time. Add individual entries with `.input("key", "value")` or a batch with `.inputs(map)`. Run-time values passed to `run(Map)` are merged on top; run-time values win on conflicts. See [Template Variables guide](../guides/template-variables.md). |
| `workflow` | `Workflow` | No | `null` (inferred) | Execution strategy. `SEQUENTIAL`, `HIERARCHICAL`, or `PARALLEL`. When `null` (not set), the framework infers: if any task declares a `context` dependency on another ensemble task, `PARALLEL` is inferred; otherwise `SEQUENTIAL`. An explicit value always overrides inference. See [Workflows guide](../guides/workflows.md#workflow-inference). |
| `managerLlm` | `ChatModel` | No | First agent's LLM | LLM for the auto-created Manager agent (hierarchical workflow only). |
| `managerMaxIterations` | `int` | No | `20` | Maximum tool-call iterations for the Manager agent. Must be greater than zero (hierarchical only). |
| `managerPromptStrategy` | `ManagerPromptStrategy` | No | `DefaultManagerPromptStrategy.DEFAULT` | Strategy that builds the Manager agent's system and user prompts. Implement `ManagerPromptStrategy` to inject domain-specific context without forking internals. Only exercised for hierarchical workflow. See [Workflows guide](../guides/workflows.md#customizing-the-manager-prompt). |
| `parallelErrorStrategy` | `ParallelErrorStrategy` | No | `FAIL_FAST` | Error handling for parallel workflow. `FAIL_FAST` stops on first failure; `CONTINUE_ON_ERROR` lets independent tasks finish and reports all failures in a `ParallelExecutionException`. |
| `verbose` | `boolean` | No | `false` | When `true`, elevates all agent logging to INFO level. |
| `memoryStore` | `MemoryStore` | No | `null` | Scoped memory store for cross-execution persistence (v2.0.0). Tasks with declared memory scopes automatically read from and write to this store. Use `MemoryStore.inMemory()` for development/testing or `MemoryStore.embeddings(model, store)` for production. See [Memory guide](../guides/memory.md). |
| `maxDelegationDepth` | `int` | No | `3` | Maximum peer-delegation depth. Applies when agents have `allowDelegation = true`. Must be greater than zero. |
| `delegationPolicies` | `List<DelegationPolicy>` | No | `[]` | Pluggable hooks evaluated before each delegation attempt (after built-in guards). Add individual policies with `.delegationPolicy(policy)` (Lombok singular) or a collection with `.delegationPolicies(list)`. Policies run in registration order. A `REJECT` result blocks the delegation; a `MODIFY` result replaces the request; an `ALLOW` result continues evaluation. Applies to both peer and hierarchical delegation. See [Delegation guide](../guides/delegation.md#delegation-policy-hooks). |
| `hierarchicalConstraints` | `HierarchicalConstraints` | No | `null` | Optional guardrails for the delegation graph (hierarchical workflow only). Enforces required workers, allowed workers, per-worker caps, global delegation cap, and stage ordering. When requiredWorkers are not called by run end, ConstraintViolationException is thrown. See the Delegation guide for full documentation. |
| `costConfiguration` | `CostConfiguration` | No | `null` | Optional per-token cost rates. When set, each task's LLM token usage is multiplied by `inputTokenRate` / `outputTokenRate` to produce a `CostEstimate` on both `TaskMetrics` and `ExecutionMetrics`. Requires the LLM provider to return token usage metadata. See [Metrics guide](../guides/metrics.md#cost-estimation). |
| `traceExporter` | `ExecutionTraceExporter` | No | `null` | Optional exporter called at the end of each `run()` with the complete `ExecutionTrace`. Use `JsonTraceExporter` to write traces to JSON files, or implement the functional interface for custom destinations. See [Metrics guide](../guides/metrics.md#automatic-export). |
| `captureMode` | `CaptureMode` | No | `OFF` | Depth of data collection during execution. `OFF` captures the base trace (prompts, tool args/results, timing, tokens). `STANDARD` also captures full LLM message history per iteration and wires memory operation counts. `FULL` adds auto-export to `./traces/` and enriched tool I/O with parsed JSON arguments. Can also be activated without code changes via the `agentensemble.captureMode` JVM system property or `AGENTENSEMBLE_CAPTURE_MODE` environment variable (resolution order: builder > system property > env var > OFF). `CaptureMode.OFF` has zero performance overhead beyond the always-active base trace. See [CaptureMode guide](../guides/capture-mode.md). |
| `reviewHandler` | `ReviewHandler` | No | `null` | Optional review handler for human-in-the-loop gates (requires `agentensemble-review` on the classpath). When set, the workflow executor fires review gates at configured timing points. Built-in factories: `ReviewHandler.console()`, `ReviewHandler.autoApprove()`, `ReviewHandler.autoApproveWithDelay(Duration)`, `ReviewHandler.web(URI)` (stub). See [Review guide](../guides/review.md). |
| `reviewPolicy` | `ReviewPolicy` | No | `null` (treated as `NEVER`) | Ensemble-level policy controlling when after-execution review gates fire for tasks without an explicit `.review()` configuration. `NEVER` (default): only tasks with `.review(Review.required())` fire. `AFTER_EVERY_TASK`: all tasks fire; tasks with `.review(Review.skip())` are exempt. `AFTER_LAST_TASK`: only the final task fires. Requires `reviewHandler` to be set. See [Review guide](../guides/review.md). |

---

## Validation

At `Ensemble.run()` time:

- At least one task must be registered
- Every task must have an LLM source: explicit `agent`, task-level `chatLanguageModel`, or ensemble-level `chatLanguageModel`
- No circular context dependencies
- Context task ordering is valid (sequential workflow only)
- `maxDelegationDepth` must be greater than zero

---

## Output: `EnsembleOutput`

`ensemble.run()` returns an `EnsembleOutput` with:

| Method | Type | Description |
|---|---|---|
| `getRaw()` | `String` | Raw text of the final task output (or manager synthesis in hierarchical workflow) |
| `getTaskOutputs()` | `List<TaskOutput>` | All task outputs in execution order |
| `getTotalDuration()` | `Duration` | Wall-clock time for the entire run |
| `getTotalToolCalls()` | `int` | Total number of tool calls across all agents |
| `getMetrics()` | `ExecutionMetrics` | Aggregated metrics: total token counts, LLM latency, tool execution time, cost estimate, etc. |
| `getTrace()` | `ExecutionTrace` | Complete execution trace with every LLM interaction, tool call, prompt text, and delegation record. Serializes to JSON via `getTrace().toJson()`. |
| `getExitReason()` | `ExitReason` | Why the run terminated: `COMPLETED` (full run), `USER_EXIT_EARLY` (reviewer chose ExitEarly), `TIMEOUT` (review gate expired with EXIT_EARLY action), or `ERROR` (unrecoverable exception). When not `COMPLETED`, `getTaskOutputs()` contains only the tasks that finished before the termination signal. |
| `isComplete()` | `boolean` | `true` when all tasks finished with `ExitReason.COMPLETED`. Convenience shorthand for `getExitReason() == ExitReason.COMPLETED`. |
| `completedTasks()` | `List<TaskOutput>` | All task outputs that finished before (or caused) the termination signal. Always safe to call regardless of exit reason. Alias for `getTaskOutputs()`. |
| `lastCompletedOutput()` | `Optional<TaskOutput>` | The last `TaskOutput` in the completed list, or empty if no tasks completed. |
| `getOutput(Task task)` | `Optional<TaskOutput>` | Output for a specific task, using object identity for the lookup. Pass the same `Task` instance given to `.task(...)` on the builder. Returns empty if the task did not complete or was never started. |

Each `TaskOutput` contains:

| Method | Type | Description |
|---|---|---|
| `getRaw()` | `String` | Raw text output from the agent |
| `getAgentRole()` | `String` | Role of the agent that produced this output |
| `getTaskDescription()` | `String` | Description of the task (after template resolution) |
| `getDuration()` | `Duration` | Time taken for this task |
| `getToolCallCount()` | `int` | Number of tool calls made for this task |
| `getCompletedAt()` | `Instant` | Timestamp when this task completed |
| `getMetrics()` | `TaskMetrics` | Per-task metrics: input/output token counts, LLM latency, tool execution time, prompt build time, cost estimate. Token counts are `-1` when the provider did not return usage metadata. |
| `getTrace()` | `TaskTrace` | Complete task trace: all LLM interactions with their tool calls, system/user prompts, delegation records, and final output. |

---

## Full Examples

**Zero-ceremony:**

```java
EnsembleOutput output = Ensemble.run(model,
    Task.of("Research AI agents"),
    Task.of("Write a blog post based on the research"));
```

**Explicit agents with memory:**

```java
EnsembleOutput output = Ensemble.builder()
    .task(researchTask)     // researchTask has .agent(researcher)
    .task(writeTask)        // writeTask has .agent(writer)
    .task(editTask)         // editTask has .agent(editor)
    .workflow(Workflow.SEQUENTIAL)
    .input("topic", "AI agents")
    .input("audience", "developers")
    .verbose(false)
    .memoryStore(MemoryStore.inMemory())
    .maxDelegationDepth(2)
    .build()
    .run();
```

**Synthesized agents with LLM-based synthesis:**

```java
EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .agentSynthesizer(AgentSynthesizer.llmBased())
    .task(Task.of("Research AI trends"))
    .task(Task.of("Analyse the findings"))
    .task(Task.of("Write an executive summary"))
    .workflow(Workflow.SEQUENTIAL)
    .build()
    .run();
```

---

## Template Variables

Use `.input("key", "value")` on the builder to supply `{variable}` placeholder values for all task descriptions and expected outputs. For dynamic multi-run scenarios, pass values to `run(Map<String, String>)` instead; those values are merged on top of any builder inputs. See [Template Variables guide](../guides/template-variables.md).

---

## MapReduceEnsemble

`MapReduceEnsemble<T>` automates the fan-out / tree-reduce pattern, keeping each reducer's
context bounded. It supports two strategies, selected by the fields you configure:
- **Static** (`chunkSize`): DAG pre-built at `build()` time.
- **Adaptive** (`targetTokenBudget`): DAG built at runtime from actual token counts.

All fields listed below are in `MapReduceEnsemble.Builder<T>`.

See the [MapReduceEnsemble guide](../guides/map-reduce.md) and
[Kitchen example](../examples/map-reduce.md) for full documentation.

### Required fields

| Field | Type | Description |
|---|---|---|
| `items` | `List<T>` | Input items to fan out over. Must not be null or empty. |
| `mapAgent` | `Function<T, Agent>` | Factory called once per item to create the map-phase agent. |
| `mapTask` | `BiFunction<T, Agent, Task>` | Factory called once per item to create the map-phase task. |
| `reduceAgent` | `Supplier<Agent>` | Factory called once per reduce group (including the final reduce). |
| `reduceTask` | `BiFunction<Agent, List<Task>, Task>` | Factory for reduce tasks. Must wire `.context(chunkTasks)`. |

### Strategy selection (one or neither)

`chunkSize` and `targetTokenBudget` are **mutually exclusive**. When neither is set,
the default is static mode with `chunkSize=5`.

| Field | Type | Default | Description |
|---|---|---|---|
| `chunkSize` | `int` | `5` | **Static mode.** Maximum upstream tasks per reduce group. Must be `>= 2`. Mutually exclusive with `targetTokenBudget`. |
| `targetTokenBudget` | `int` | -- | **Adaptive mode.** Token limit per reduce group. Must be `> 0`. Mutually exclusive with `chunkSize`. |
| `contextWindowSize` | `int` | -- | **Adaptive mode (convenience).** Derives `targetTokenBudget = contextWindowSize * budgetRatio`. Must be set together with `budgetRatio`. |
| `budgetRatio` | `double` | `0.5` | **Adaptive mode (convenience).** Fraction of context window to use. Range: `(0.0, 1.0]`. Must be set together with `contextWindowSize`. |

### Adaptive-only optional fields

| Field | Type | Default | Description |
|---|---|---|---|
| `maxReduceLevels` | `int` | `10` | Maximum adaptive reduce iterations before the final reduce is forced. Must be `>= 1`. |
| `tokenEstimator` | `Function<String, Integer>` | built-in | Custom token estimator function. Overrides heuristic fallback (`rawText.length() / 4`) when the LLM provider does not return token counts. |
| `directAgent` | `Supplier<Agent>` | `null` | **Short-circuit (adaptive only).** Factory for the agent that executes the single direct task when the total estimated input size fits within `targetTokenBudget`. Must be set together with `directTask`; setting one without the other is a `ValidationException`. Not allowed in static (`chunkSize`) mode. |
| `directTask` | `BiFunction<Agent, List<T>, Task>` | `null` | **Short-circuit (adaptive only).** Factory for the direct task. Receives the agent and the complete `List<T>` of all items. Must be set together with `directAgent`; setting one without the other is a `ValidationException`. Not allowed in static (`chunkSize`) mode. |
| `inputEstimator` | `Function<T, String>` | `Object::toString` | **Short-circuit estimator (used only in adaptive mode).** Converts each input item to a text representation used for pre-execution input size estimation in adaptive short-circuiting. Defaults to `toString()`. Provide a compact representation (e.g., a JSON summary) when `toString()` is verbose or not representative of the context window cost. May also be set in static (`chunkSize`) mode, where it is accepted but ignored. Can be set without `directAgent`/`directTask` (no constraint). |

### Common optional fields

| Field | Type | Default | Description |
|---|---|---|---|
| `verbose` | `boolean` | `false` | Passed through to the inner `Ensemble`. |
| `listener` | `EnsembleListener` | -- | Repeatable listener registration. |
| `captureMode` | `CaptureMode` | `OFF` | Passed through to the inner `Ensemble`. |
| `parallelErrorStrategy` | `ParallelErrorStrategy` | `FAIL_FAST` | Passed through to the inner `Ensemble`. |
| `costConfiguration` | `CostConfiguration` | `null` | Passed through to the inner `Ensemble`. |
| `traceExporter` | `ExecutionTraceExporter` | `null` | Passed through to the inner `Ensemble`. |
| `toolExecutor` | `Executor` | virtual-thread | Passed through to the inner `Ensemble`. |
| `toolMetrics` | `ToolMetrics` | `NoOpToolMetrics` | Passed through to the inner `Ensemble`. |
| `input` / `inputs` | `Map<String,String>` | `{}` | Template variable inputs. |

### Methods

| Method | Returns | Notes |
|---|---|---|
| `build()` | `MapReduceEnsemble<T>` | Validates and builds. Static: constructs full DAG. Adaptive: stores configuration for runtime execution. |
| `run()` | `EnsembleOutput` | Executes; returns result from the final reduce task. |
| `run(Map<String,String>)` | `EnsembleOutput` | Runtime variable overrides merged on top of builder inputs. |
| `toEnsemble()` | `Ensemble` | **Static mode only.** Pre-built inner ensemble for devtools inspection. Throws `UnsupportedOperationException` in adaptive mode. |
| `isAdaptiveMode()` | `boolean` | Returns `true` when `targetTokenBudget` was set (directly or derived). |
