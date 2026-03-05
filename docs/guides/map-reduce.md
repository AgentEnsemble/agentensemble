# MapReduceEnsemble Guide

`MapReduceEnsemble` solves the context window overflow problem that arises when using
`Workflow.PARALLEL` to fan out to a large number of agents. It supports two reduction
strategies:

- **Static** (`chunkSize`): the DAG is pre-built at `build()` time with a fixed group size.
  Use when N and average output sizes are predictable.
- **Adaptive** (`targetTokenBudget`): the DAG is built level-by-level at runtime based on
  actual output token counts. Use when output sizes vary or context window overflow is a
  hard constraint.

---

## Problem: context window limits at scale

With a standard parallel workflow, the aggregation task receives all N outputs as context:

```java
// All N outputs -> one aggregator: context = N * avg_output_size
Task aggregate = Task.builder()
    .context(allMapTasks)  // grows without bound
    ...
    .build();
```

For small N this is fine. But when N grows (e.g. 20+ items) or each map output is verbose,
the aggregator's context can exceed the model's limit -- or degrade silently as context quality
drops with excessively long inputs.

`MapReduceEnsemble` solves this with a tree-reduction:

```
N=7, chunkSize=3:

  Map level:   [M1] [M2] [M3]  [M4] [M5] [M6]  [M7]
                    \               \
  Reduce L1:   [R-0]               [R-1]         [R-2]
                    \               /             /
  Final reduce:            [Final]
```

Each reducer receives at most `chunkSize` inputs. Tree depth is O(log_K(N)).

---

## Quick start

```java
record DishResult(String dish, List<String> ingredients, int prepMinutes, String plating) {}

EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
    .items(order.getItems())                          // items to fan out over

    // Map phase: one agent + task per item
    .mapAgent(item -> Agent.builder()
        .role(item.getDish() + " Chef")
        .goal("Prepare " + item.getDish())
        .llm(model)
        .build())
    .mapTask((item, agent) -> Task.builder()
        .description("Execute the recipe for: " + item.getDish())
        .expectedOutput("Recipe with ingredients, steps, and timing")
        .agent(agent)
        .outputType(DishResult.class)
        .build())

    // Reduce phase: consolidate groups of chunkSize map outputs
    .reduceAgent(() -> Agent.builder()
        .role("Sub-Chef")
        .goal("Consolidate dish preparations")
        .llm(model)
        .build())
    .reduceTask((agent, chunkTasks) -> Task.builder()
        .description("Consolidate these dish preparations.")
        .expectedOutput("Consolidated plan")
        .agent(agent)
        .context(chunkTasks)  // wire context explicitly
        .build())

    .chunkSize(3)
    .build()
    .run();
```

---

## Builder reference

### Required fields

| Field | Type | Description |
|---|---|---|
| `items` | `List<T>` | Input items. Must not be null or empty. |
| `mapAgent` | `Function<T, Agent>` | Factory called once per item to create the map-phase agent. |
| `mapTask` | `BiFunction<T, Agent, Task>` | Factory called once per item to create the map-phase task. Receives the item and the agent produced by `mapAgent`. |
| `reduceAgent` | `Supplier<Agent>` | Factory called once per reduce group (at every level) to create the reduce agent. |
| `reduceTask` | `BiFunction<Agent, List<Task>, Task>` | Factory called once per reduce group. Receives the agent and the upstream tasks for that group. **Must call `.context(chunkTasks)` on the returned task.** |

### Optional fields

| Field | Type | Default | Description |
|---|---|---|---|
| `chunkSize` | `int` | `5` | Maximum number of upstream tasks per reduce group. Must be `>= 2`. |
| `verbose` | `boolean` | `false` | Elevates execution logging to INFO level. |
| `listener` | `EnsembleListener` | -- | Register event listeners (repeatable). |
| `captureMode` | `CaptureMode` | `OFF` | Data collection depth. |
| `parallelErrorStrategy` | `ParallelErrorStrategy` | `FAIL_FAST` | How to handle failures in map or reduce tasks. |
| `costConfiguration` | `CostConfiguration` | `null` | Optional per-token cost rates. |
| `traceExporter` | `ExecutionTraceExporter` | `null` | Optional trace exporter. |
| `toolExecutor` | `Executor` | virtual-thread | Executor for parallel tool calls. |
| `toolMetrics` | `ToolMetrics` | `NoOpToolMetrics` | Metrics backend for tool execution. |
| `input` / `inputs` | `Map<String,String>` | `{}` | Template variable inputs. |

### Methods

| Method | Returns | Description |
|---|---|---|
| `build()` | `MapReduceEnsemble<T>` | Validates configuration, builds the DAG, returns a ready instance. |
| `run()` | `EnsembleOutput` | Executes and returns the final output. |
| `run(Map<String,String>)` | `EnsembleOutput` | Run with additional template variable overrides. |
| `toEnsemble()` | `Ensemble` | Returns the pre-built inner `Ensemble` for devtools inspection. |

---

## Static DAG construction algorithm

Given N items and chunkSize K:

1. Create N map agents and tasks (no context, all independent).
2. If N <= K: final reduce gets context = all N map tasks directly (2 levels total).
3. If N > K: partition map tasks into groups of at most K. Create one reduce agent + task per
   group. If the resulting reduce level has more than K tasks, partition again and repeat.
   When the level has <= K tasks, create the final reduce task.

**Tree depth:** O(log_K(N)).

| N | K | Levels | Total tasks |
|---|---|---|---|
| 1 | any | 2 (1 map + 1 final) | 2 |
| 5 | 5 | 2 (5 map + 1 final) | 6 |
| 7 | 3 | 3 (7 map + 3 L1 + 1 final) | 11 |
| 25 | 5 | 3 (25 map + 5 L1 + 1 final) | 31 |
| 26 | 5 | 4 (26 map + 6 L1 + 2 L2 + 1 final) | 35 |

---

## Choosing `chunkSize`

`chunkSize` controls how many map outputs each reducer reads. Trade-offs:

- **Larger chunkSize**: fewer reduce levels (shallower tree), but each reducer has more
  context. Use when outputs are compact and the model handles larger context well.
- **Smaller chunkSize**: more reduce levels, each with less context per reducer. Use when
  map outputs are verbose or the model has a limited context window.

A rule of thumb: estimate `chunkSize * avg_output_tokens` and ensure this stays well
within your model's context limit. For a model with 128K tokens and ~500 token outputs,
`chunkSize=50` gives comfortable margin; for 2K token outputs, `chunkSize=10-20` is safer.

---

## Wiring context in the reduce task

The reduce task factory receives `chunkTasks` -- the list of upstream Task objects. You
**must** wire these as context on the returned task. The framework does not mutate the
returned task:

```java
.reduceTask((agent, chunkTasks) -> Task.builder()
    .description("Consolidate these preparations.")
    .expectedOutput("Consolidated plan")
    .agent(agent)
    .context(chunkTasks)  // required -- without this, the inner Ensemble will throw ValidationException
    .build())
```

---

## Structured output in the map phase

Structured output works naturally in map tasks. The LLM produces JSON that is parsed into
the target type. Reduce tasks receive the structured output as their context:

```java
record RecipeResult(String dish, List<String> ingredients, int prepMinutes) {}

.mapTask((item, agent) -> Task.builder()
    .description("Prepare recipe for " + item.name())
    .expectedOutput("Recipe result as JSON")
    .agent(agent)
    .outputType(RecipeResult.class)  // structured output
    .build())
```

---

## Error handling

Map and reduce tasks are executed with `Workflow.PARALLEL` internally. The
`parallelErrorStrategy` field (default: `FAIL_FAST`) controls failure behavior:

- **`FAIL_FAST`** (default): first failure throws `TaskExecutionException` and stops all
  remaining tasks in that level.
- **`CONTINUE_ON_ERROR`**: failed tasks are skipped; tasks that depend on them are also
  skipped. If any task fails, `ParallelExecutionException` is thrown at the end, carrying
  completed outputs and failure details.

---

## DAG inspection and visualization

Call `toEnsemble()` to access the pre-built inner `Ensemble` for devtools inspection
or DAG export before execution:

```java
MapReduceEnsemble<OrderItem> mre = MapReduceEnsemble.<OrderItem>builder()
    ...
    .build();

// Inspect structure
System.out.printf("DAG: %d agents, %d tasks%n",
    mre.toEnsemble().getAgents().size(),
    mre.toEnsemble().getTasks().size());

// Export enriched DAG with map/reduce node metadata for agentensemble-viz
DagModel dag = DagExporter.build(mre);  // includes nodeType, mapReduceLevel, mapReduceMode
dag.toJson(Path.of("./traces/kitchen.dag.json"));
```

The `DagExporter.build(MapReduceEnsemble)` overload enriches each task node with:
- `nodeType`: `"map"`, `"reduce"`, or `"final-reduce"`
- `mapReduceLevel`: `0` for map, `1+` for reduce levels
- `mapReduceMode`: `"STATIC"` on the `DagModel`

`agentensemble-viz` renders these with distinct badges (MAP, REDUCE Ln, AGGREGATE).

---

## Adaptive mode (`targetTokenBudget`)

Instead of a fixed `chunkSize`, adaptive mode measures actual output token counts after
each level and bins them into groups that collectively fit within `targetTokenBudget`.
This eliminates the need to guess output sizes upfront.

### How it works

```
STEP 1: Run N map tasks in parallel.
STEP 2: Estimate total output tokens.
  - If total <= targetTokenBudget: run one final reduce, done.
  - Else: go to step 3.
STEP 3: Bin-pack outputs (first-fit-decreasing) into groups of <= targetTokenBudget.
        Run one reduce per bin in parallel.
STEP 4: Repeat from step 2 with reduce outputs, until within budget
        or maxReduceLevels is reached.
STEP 5: Final reduce.
```

### Adaptive quick start

```java
EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
    .items(order.getItems())
    .mapAgent(item -> Agent.builder()
        .role(item.getDish() + " Chef")
        .goal("Prepare " + item.getDish())
        .llm(model)
        .build())
    .mapTask((item, agent) -> Task.builder()
        .description("Execute recipe for: " + item.getDish())
        .expectedOutput("Recipe with ingredients, steps, and timing")
        .agent(agent)
        .build())
    .reduceAgent(() -> Agent.builder()
        .role("Sub-Chef")
        .goal("Consolidate dish preparations")
        .llm(model)
        .build())
    .reduceTask((agent, chunkTasks) -> Task.builder()
        .description("Consolidate these dish preparations.")
        .expectedOutput("Consolidated plan")
        .agent(agent)
        .context(chunkTasks)  // same as static mode -- wire context explicitly
        .build())

    // Adaptive strategy: keep reducing until total context < 8000 tokens
    .targetTokenBudget(8_000)
    .maxReduceLevels(10)   // safety valve (default: 10)
    .build()
    .run();
```

Or derive the budget from the model's context window:

```java
.contextWindowSize(128_000)   // model context window in tokens
.budgetRatio(0.5)             // use at most 50% -> budget = 64_000 tokens
```

### Adaptive builder fields

| Field | Type | Default | Description |
|---|---|---|---|
| `targetTokenBudget` | `int` | -- | Token limit per reduce group. Must be > 0. Mutually exclusive with `chunkSize`. |
| `contextWindowSize` | `int` | -- | Convenience: derives `targetTokenBudget = contextWindowSize * budgetRatio`. Must be set together with `budgetRatio`. |
| `budgetRatio` | `double` | `0.5` | Fraction of context window for reduce input. Range: `(0.0, 1.0]`. Must be set together with `contextWindowSize`. |
| `maxReduceLevels` | `int` | `10` | Maximum adaptive reduce levels before final reduce is forced. Must be >= 1. |
| `tokenEstimator` | `Function<String, Integer>` | built-in | Custom token estimator. Overrides the heuristic fallback when the LLM provider does not return token counts. |

### Token estimation

The adaptive executor determines token counts using a three-tier strategy:

1. **Provider count** (highest priority): `TaskOutput.getMetrics().getOutputTokens()` when
   the LLM provider returns a non-negative value.
2. **Custom estimator**: the `tokenEstimator` function, if provided.
3. **Heuristic fallback**: `rawOutput.length() / 4`. A WARN is logged when this is used.

For accurate bin-packing, prefer using a model that returns token usage metadata. If the
provider does not, supply a custom estimator using a tokenizer library:

```java
.tokenEstimator(text -> myTokenizer.countTokens(text))
```

### `toEnsemble()` in adaptive mode

In adaptive mode, `toEnsemble()` throws `UnsupportedOperationException` because the DAG
shape is not known until runtime. Instead, inspect the aggregated `ExecutionTrace` after
execution, or use `DagExporter.build(output.getTrace())` for a post-execution DAG:

```java
EnsembleOutput output = mre.run();

// Post-execution DAG export for visualization
DagModel dag = DagExporter.build(output.getTrace());
dag.toJson(Path.of("./traces/adaptive-run.dag.json"));

// Per-level timing breakdown
output.getTrace().getMapReduceLevels().forEach(level ->
    System.out.printf("Level %d: %d tasks, duration=%s%n",
        level.getLevel(), level.getTaskCount(), level.getDuration()));
```

### Adaptive execution trace

The aggregated `ExecutionTrace` from an adaptive run has:
- `workflow = "MAP_REDUCE_ADAPTIVE"`
- `mapReduceLevels`: list of per-level summaries (level index, task count, duration)
- All `TaskTrace` objects annotated with `mapReduceLevel` (int) and `nodeType` (String)
- `ExecutionMetrics` summed across all levels

### Static vs adaptive: when to use each

| Scenario | Recommended strategy |
|---|---|
| N is known, output sizes are predictable | **Static** (`chunkSize`) |
| Want to inspect/export the DAG before running | **Static** (`toEnsemble()` works) |
| Same inputs always produce the same tree shape | **Static** (deterministic) |
| Output sizes vary significantly across agents | **Adaptive** (`targetTokenBudget`) |
| Context window overflow is a hard constraint | **Adaptive** (measures actual sizes) |
| LLM provider returns token usage metadata | **Adaptive** (most accurate) |

---

## Comparison with plain `Workflow.PARALLEL`

| Feature | `Workflow.PARALLEL` (manual) | `MapReduceEnsemble` |
|---|---|---|
| Aggregator context size | N * avg_output (unbounded) | chunkSize * avg_output (bounded) |
| DAG construction | Manual, error-prone | Automatic, O(log_K(N)) depth |
| `toEnsemble()` / devtools | Standard `Ensemble` | Enriched with map-reduce metadata |
| Structured output | Supported | Supported |
| Error handling | `parallelErrorStrategy` | Same `parallelErrorStrategy` |
| Template variables | `.input()` / `run(Map)` | Same API |

Use plain `Workflow.PARALLEL` when N is small (e.g. < 10) and output sizes are compact.
Use `MapReduceEnsemble` when N is large, outputs are verbose, or context window overflow
is a concern.
