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
| `tasks` | `List<Task>` | Yes* | -- | All tasks to execute. Add with `.task(t)` (singular) or `.tasks(list)`. *At least one of `tasks`, `loops`, or `phases` is required. Cannot be combined with `phases`. |
| `loops` | `List<Loop>` | Yes* | -- | Bounded iteration loops over a sub-ensemble of tasks. Add with `.loop(loop)`. Loops are first-class workflow nodes; in `SEQUENTIAL` workflow they run in declaration order after the task list, in `PARALLEL` they run sequentially after the task DAG completes. Cannot be combined with `phases`; rejected with `Workflow.HIERARCHICAL`. See [Loops guide](../guides/loops.md). |
| `graph` | `Graph` | Yes* | `null` | Optional state-machine workflow with named states (Tasks) and conditional / unconditional edges, including arbitrary back-edges. Add with `.graph(graph)`. **Mutually exclusive** with `tasks`, `loops`, and `phases` -- a Graph ensemble has exactly one Graph and no other workflow nodes. Rejected with `Workflow.HIERARCHICAL`. See [Graphs guide](../guides/graphs.md). |
| `phases` | `List<Phase>` | Yes* | -- | Named task-group workstreams with a dependency DAG. Add with `.phase(phase)` (singular). Independent phases run in parallel; a phase starts only when all its `after()` predecessors have completed. *Required when not using tasks. Cannot be combined with `tasks` or `loops`. See [Phases guide](../guides/phases.md). |
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
| `contextFormat` | `ContextFormat` | No | `JSON` | Serialization format for structured data in LLM prompts. `JSON` (default) uses standard Jackson serialization. `TOON` uses [TOON format](https://github.com/toon-format/spec) for 30-60% token reduction. Requires `dev.toonformat:jtoon` on classpath when `TOON` is selected. See [TOON Format guide](../guides/toon-format.md). |
| `maxToolOutputLength` | `int` | No | `-1` (unlimited) | Maximum characters of tool output sent to the LLM per tool call. `-1` means no truncation. When positive, results are truncated before being added to the LLM message history; a note is appended so the model knows output was cut. The full output is always stored in the trace and fired to listeners regardless of this setting. Can be overridden per-run via `RunOptions`. |
| `toolLogTruncateLength` | `int` | No | `200` | Maximum characters of tool input/output emitted to log statements (INFO/WARN level). `-1` means full output is logged; `0` suppresses output content from logs entirely. Does not affect what the LLM sees. Can be overridden per-run via `RunOptions`. |
| `drainTimeout` | `Duration` | No | `5 minutes` | Maximum time to wait for in-flight work to complete during graceful shutdown. Only relevant in long-running mode (`start(port)`/`stop()`). See [Long-Running Ensembles guide](../guides/long-running-ensembles.md). |
| `shareTask(name, task)` | builder method | No | -- | Share a named task with the network. Other ensembles will be able to delegate work to it via the forthcoming network delegation API (EN-004). Names must be unique within the ensemble. See [Long-Running Ensembles guide](../guides/long-running-ensembles.md). |
| `shareTool(name, tool)` | builder method | No | -- | Share a named tool with the network. Other ensembles' agents will be able to invoke it directly via the forthcoming network tool API (EN-005). Names must be unique within the ensemble. See [Long-Running Ensembles guide](../guides/long-running-ensembles.md). |

---

## Phase Configuration

`Phase` is declared separately and registered with `Ensemble.builder().phase(phase)`.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `name` | `String` | Yes | -- | Unique name within the ensemble. Used in logs, traces, and `phaseOutputs` map keys. Must not be null or blank. |
| `tasks` | `List<Task>` | Yes | -- | Tasks to execute within this phase. Must contain at least one task. Add with `.task(t)`. |
| `workflow` | `Workflow` | No | `null` (inherits) | Workflow strategy for internal task execution. When `null`, inherits the ensemble-level `workflow`. `HIERARCHICAL` is not permitted at the phase level. |
| `after` | `List<Phase>` | No | `[]` | Predecessor phases. This phase will not start until all declared predecessors have completed. A phase with no `after()` declarations is a root phase and starts immediately. Declare with `.after(phase)` (single) or `.after(phaseA, phaseB, ...)` (varargs). |

### Static Factory

```java
Phase.of(String name, Task... tasks)
Phase.of(String name, List<Task> tasks)
```

### Validation

At `Ensemble.build()` time (when phases are used):

| Rule | Error |
|---|---|
| Cannot mix `.task()` and `.phase()` on same builder | `ValidationException` |
| Phase name must not be null or blank | `ValidationException` |
| Phase name must be unique within ensemble | `ValidationException` |
| Each phase must contain at least one task | `ValidationException` |
| Phase DAG must be acyclic | `ValidationException` |
| Phase `workflow` must not be `HIERARCHICAL` | `ValidationException` |
| Cross-phase `context()` reference must point to a task in a predecessor phase | `ValidationException` |

---

## Validation

At `Ensemble.run()` time:

- At least one task or phase must be registered
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

## run() Overloads

| Method | Description |
|---|---|
| `run()` | Execute using builder inputs and all builder-level settings. |
| `run(Map<String,String> inputs)` | Merge run-time template variable values on top of builder inputs. |
| `run(RunOptions options)` | Apply per-run overrides for `maxToolOutputLength` and `toolLogTruncateLength`. Builder defaults are used for any field left `null` in `RunOptions`. |
| `run(Map<String,String> inputs, RunOptions options)` | Combine template variable overrides with per-run option overrides. |

`RunOptions` is constructed with `RunOptions.builder()`. Fields not set on the builder remain `null` and inherit the ensemble default.

```java
// Full LLM output; terse logs
ensemble.run(RunOptions.builder()
    .maxToolOutputLength(-1)
    .toolLogTruncateLength(500)
    .build());

// Full log output for this debugging run only (LLM limit inherits the ensemble default)
ensemble.run(RunOptions.builder()
    .toolLogTruncateLength(-1)
    .build());

// Combine with template variable overrides
ensemble.run(Map.of("topic", "AI"), RunOptions.builder()
    .maxToolOutputLength(5000)
    .build());
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

---

## Transport Configuration

The Transport SPI abstracts the message delivery mechanism between ensembles. It is used
standalone or will be wired into the future `EnsembleNetwork.builder()`.

### Transport Interface

Each transport instance is bound to an ensemble name that identifies its inbox.

| Method | Description |
|---|---|
| `send(WorkRequest)` | Send a work request to this transport's inbox |
| `receive(Duration)` | Receive the next work request from this transport's inbox (blocks up to timeout, returns null on timeout) |
| `deliver(WorkResponse)` | Deliver a work response back to the requester |

### Factory Methods

| Factory | Description |
|---|---|
| `Transport.websocket(ensembleName)` | Simple mode bound to the given ensemble inbox. In-process queues, no external infrastructure. |
| `Transport.websocket()` | Simple mode with a default ensemble name of `"default"`. Convenience for single-ensemble scenarios. |

### RequestQueue SPI

Pluggable queue for work request delivery between ensembles.

| Method | Description |
|---|---|
| `enqueue(queueName, request)` | Enqueue a work request for a target ensemble |
| `dequeue(queueName, timeout)` | Dequeue the next request (blocking); returns null on timeout |
| `acknowledge(queueName, requestId)` | Acknowledge successful processing |
| `RequestQueue.inMemory()` | In-memory implementation for development |

### ResultStore SPI

Pluggable key-value store for work responses.

| Method | Description |
|---|---|
| `store(requestId, response, ttl)` | Store a response with a TTL |
| `retrieve(requestId)` | Retrieve a stored response; returns null if not found |
| `subscribe(requestId, callback)` | Subscribe for notification when a result is stored |
| `ResultStore.inMemory()` | In-memory implementation for development |

### WorkResponse

Standardized response envelope mirroring `WorkRequest`.

| Field | Type | Required | Description |
|---|---|---|---|
| `requestId` | `String` | Yes | Correlation key matching the original request |
| `status` | `String` | Yes | Outcome: "COMPLETED", "FAILED", or "REJECTED" |
| `result` | `String` | No | Output on success |
| `error` | `String` | No | Error message on failure/rejection |
| `durationMs` | `Long` | No | Execution duration in milliseconds |


---

## Ensemble Control API (Phase 1)

New `WebDashboard.Builder` fields for the HTTP-based Control API.

### ToolCatalog

Immutable registry mapping tool names to `AgentTool` instances.

```java
ToolCatalog catalog = ToolCatalog.builder()
    .tool("web_search", webSearchTool)
    .tool("calculator", calculatorTool)
    .build();
```

| Method | Returns | Description |
|--------|---------|-------------|
| `resolve(name)` | `AgentTool` | Returns the tool; throws `NoSuchElementException` if not found |
| `find(name)` | `Optional<AgentTool>` | Returns empty Optional if not found |
| `list()` | `List<ToolInfo>` | All registered tools in insertion order |
| `contains(name)` | `boolean` | Whether the name is registered |
| `size()` | `int` | Number of registered tools |

### ModelCatalog

Immutable registry mapping model aliases to `ChatModel` instances.

```java
ModelCatalog catalog = ModelCatalog.builder()
    .model("sonnet", sonnetModel)
    .model("haiku", haikuModel, haikuStreamingModel)
    .build();
```

| Method | Returns | Description |
|--------|---------|-------------|
| `resolve(alias)` | `ChatModel` | Returns the model; throws `NoSuchElementException` if not found |
| `find(alias)` | `Optional<ChatModel>` | Returns empty Optional if not found |
| `resolveStreaming(alias)` | `StreamingChatModel` | Returns the streaming variant; null if not registered |
| `list()` | `List<ModelInfo>` | All registered models in insertion order |
| `size()` | `int` | Number of registered models |

### WebDashboard.Builder -- Control API fields

| Builder method | Type | Default | Description |
|----------------|------|---------|-------------|
| `toolCatalog(ToolCatalog)` | `ToolCatalog` | `null` | Registered tool allowlist for API requests |
| `modelCatalog(ModelCatalog)` | `ModelCatalog` | `null` | Registered model allowlist for API requests |
| `maxConcurrentRuns(int)` | `int` | `5` | Maximum runs executing simultaneously; must be >= 1 |
| `maxRetainedCompletedRuns(int)` | `int` | `100` | Completed runs kept in memory for state queries; must be >= 1 |

### REST endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/runs` | Submit a run (Level 1: template + inputs) |
| `GET` | `/api/runs` | List retained runs; filter with `?status=` and `?tag=key:value` |
| `GET` | `/api/runs/{runId}` | Get full run detail |
| `GET` | `/api/capabilities` | List registered tools, models, and preconfigured tasks |

### RunState.Status enum

| Value | Description |
|-------|-------------|
| `ACCEPTED` | Run queued; concurrency permit acquired |
| `RUNNING` | Run executing on a virtual thread |
| `COMPLETED` | All tasks finished without error |
| `FAILED` | Run terminated with an unhandled exception |
| `CANCELLED` | Run cancelled cooperatively (Phase 3) |
| `REJECTED` | Concurrency limit reached; run never executed |
