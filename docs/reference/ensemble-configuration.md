# Ensemble Configuration Reference

All fields available on `Ensemble.builder()`.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `agents` | `List<Agent>` | Yes | -- | All agents participating in this ensemble. Add with `.agent(a)` (singular) or `.agents(list)`. |
| `tasks` | `List<Task>` | Yes | -- | All tasks to execute. Add with `.task(t)` (singular) or `.tasks(list)`. |
| `inputs` | `Map<String, String>` | No | `{}` | Template variable values applied to all task descriptions and expected outputs at run time. Add individual entries with `.input("key", "value")` or a batch with `.inputs(map)`. Run-time values passed to `run(Map)` are merged on top; run-time values win on conflicts. See [Template Variables guide](../guides/template-variables.md). |
| `workflow` | `Workflow` | No | `SEQUENTIAL` | Execution strategy. `SEQUENTIAL`, `HIERARCHICAL`, or `PARALLEL`. |
| `managerLlm` | `ChatModel` | No | First agent's LLM | LLM for the auto-created Manager agent (hierarchical workflow only). |
| `managerMaxIterations` | `int` | No | `20` | Maximum tool-call iterations for the Manager agent. Must be greater than zero (hierarchical only). |
| `managerPromptStrategy` | `ManagerPromptStrategy` | No | `DefaultManagerPromptStrategy.DEFAULT` | Strategy that builds the Manager agent's system and user prompts. Implement `ManagerPromptStrategy` to inject domain-specific context without forking internals. Only exercised for hierarchical workflow. See [Workflows guide](../guides/workflows.md#customizing-the-manager-prompt). |
| `parallelErrorStrategy` | `ParallelErrorStrategy` | No | `FAIL_FAST` | Error handling for parallel workflow. `FAIL_FAST` stops on first failure; `CONTINUE_ON_ERROR` lets independent tasks finish and reports all failures in a `ParallelExecutionException`. |
| `verbose` | `boolean` | No | `false` | When `true`, elevates all agent logging to INFO level. |
| `memory` | `EnsembleMemory` | No | `null` | Memory configuration. See [Memory Configuration reference](memory-configuration.md). |
| `maxDelegationDepth` | `int` | No | `3` | Maximum peer-delegation depth. Applies when agents have `allowDelegation = true`. Must be greater than zero. |
| `delegationPolicies` | `List<DelegationPolicy>` | No | `[]` | Pluggable hooks evaluated before each delegation attempt (after built-in guards). Add individual policies with `.delegationPolicy(policy)` (Lombok singular) or a collection with `.delegationPolicies(list)`. Policies run in registration order. A `REJECT` result blocks the delegation; a `MODIFY` result replaces the request; an `ALLOW` result continues evaluation. Applies to both peer and hierarchical delegation. See [Delegation guide](../guides/delegation.md#delegation-policy-hooks). |
| `hierarchicalConstraints` | `HierarchicalConstraints` | No | `null` | Optional guardrails for the delegation graph (hierarchical workflow only). Enforces required workers, allowed workers, per-worker caps, global delegation cap, and stage ordering. When requiredWorkers are not called by run end, ConstraintViolationException is thrown. See the Delegation guide for full documentation. |
| `costConfiguration` | `CostConfiguration` | No | `null` | Optional per-token cost rates. When set, each task's LLM token usage is multiplied by `inputTokenRate` / `outputTokenRate` to produce a `CostEstimate` on both `TaskMetrics` and `ExecutionMetrics`. Requires the LLM provider to return token usage metadata. See [Metrics guide](../guides/metrics.md#cost-estimation). |
| `traceExporter` | `ExecutionTraceExporter` | No | `null` | Optional exporter called at the end of each `run()` with the complete `ExecutionTrace`. Use `JsonTraceExporter` to write traces to JSON files, or implement the functional interface for custom destinations. See [Metrics guide](../guides/metrics.md#automatic-export). |
| `captureMode` | `CaptureMode` | No | `OFF` | Depth of data collection during execution. `OFF` captures the base trace (prompts, tool args/results, timing, tokens). `STANDARD` also captures full LLM message history per iteration and wires memory operation counts. `FULL` adds auto-export to `./traces/` and enriched tool I/O with parsed JSON arguments. Can also be activated without code changes via the `agentensemble.captureMode` JVM system property or `AGENTENSEMBLE_CAPTURE_MODE` environment variable (resolution order: builder > system property > env var > OFF). `CaptureMode.OFF` has zero performance overhead beyond the always-active base trace. See [CaptureMode guide](../guides/capture-mode.md). |

---

## Validation

At `Ensemble.run()` time:

- At least one agent must be registered
- At least one task must be registered
- Every task's `agent` must be in the ensemble's `agents` list
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

## Full Example

```java
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .agent(editor)
    .task(researchTask)
    .task(writeTask)
    .task(editTask)
    .workflow(Workflow.SEQUENTIAL)
    .input("topic", "AI agents")
    .input("audience", "developers")
    .verbose(false)
    .memory(EnsembleMemory.builder().shortTerm(true).build())
    .maxDelegationDepth(2)
    .build()
    .run();
```

---

## Template Variables

Use `.input("key", "value")` on the builder to supply `{variable}` placeholder values for all task descriptions and expected outputs. For dynamic multi-run scenarios, pass values to `run(Map<String, String>)` instead; those values are merged on top of any builder inputs. See [Template Variables guide](../guides/template-variables.md).

---

## MapReduceEnsemble

`MapReduceEnsemble<T>` constructs a static tree-reduction DAG that bounds each reducer's
context to `chunkSize` outputs. All fields listed below are in `MapReduceEnsemble.Builder<T>`.

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

### Optional fields

| Field | Type | Default | Description |
|---|---|---|---|
| `chunkSize` | `int` | `5` | Maximum upstream tasks per reduce group. Must be `>= 2`. |
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
| `build()` | `MapReduceEnsemble<T>` | Validates and builds the DAG. |
| `run()` | `EnsembleOutput` | Executes; returns result from the final reduce task. |
| `run(Map<String,String>)` | `EnsembleOutput` | Runtime variable overrides merged on top of builder inputs. |
| `toEnsemble()` | `Ensemble` | Pre-built inner ensemble for devtools inspection and DAG export. |
