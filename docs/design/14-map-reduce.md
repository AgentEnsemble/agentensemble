# 14 - MapReduceEnsemble

**Target release:** v2.0.0

This document describes the design for `MapReduceEnsemble`, a builder that automates the
fan-out / tree-reduce pattern for scenarios where a dynamic number of agents process items
independently and their results are aggregated progressively to fit within a model's context
window.

---

## 1. Problem Statement

`Workflow.PARALLEL` makes it straightforward to fan out to N agents and aggregate their
outputs via a single reduce task:

```
[Agent 1] ----+
[Agent 2] ----+--> [Aggregator] --> Final Output
[Agent N] ----+
```

The aggregator receives all N outputs as context. When N is small or each agent's output is
compact, this works well. However, when N is large or each output is verbose, the aggregator's
context may exceed the model's context window. Even before hard token limits are reached,
context quality degrades when models receive excessively long inputs.

The pattern requires multi-level tree reduction:

```
Level 0 (Map):    [A1] [A2] [A3] [A4] [A5] [A6]
                    \    |    /     \    |    /
Level 1 (Reduce): [Reduce-0-0]      [Reduce-0-1]
                         \                /
Level 2 (Final):       [Final Reducer]
```

Building this DAG by hand is error-prone and requires knowing output sizes upfront.
`MapReduceEnsemble` automates the construction.

---

## 2. Two Reduction Strategies

`MapReduceEnsemble` supports two strategies, selected by the configuration:

### 2.1 Static Strategy (`chunkSize`)

The entire DAG is pre-built before execution based on a fixed `chunkSize`. The shape of the
tree is fully determined at build time. `toEnsemble()` returns the pre-built `Ensemble`, which
can be inspected via devtools before execution.

**Execution model:** a single `Ensemble.run()` with `Workflow.PARALLEL`. The framework's
existing topological scheduler handles concurrent execution across all levels.

**When to use:**
- N and average output sizes are known and predictable
- You want to inspect or export the DAG before executing
- Determinism (same inputs = same tree shape) is required

### 2.2 Adaptive Strategy (`targetTokenBudget`)

The DAG is built level-by-level at runtime based on actual output token counts. After each
level executes, the framework measures output sizes and groups them into bins that fit within
the budget. The tree depth is determined adaptively.

**Execution model:** multiple `Ensemble.run()` calls, one per level. Each level is an
independent `Ensemble` with `Workflow.PARALLEL`.

**When to use:**
- N is large or output sizes are unpredictable
- You want to minimize unnecessary reduce levels when outputs happen to be small
- Context window constraints are a hard requirement

---

## 3. Short-Circuit Optimization (Adaptive Only)

Before the map phase runs, the framework estimates the total input size. If the estimate is
below the token budget, the map-reduce is unnecessary and an optional direct task can be used
instead:

```
Decision tree:

estimated_input_tokens <= targetTokenBudget AND directAgent/directTask configured?
  YES --> Short-circuit: run single direct task with all items as input
  NO  --> Run map phase

After map phase:
total_output_tokens <= targetTokenBudget?
  YES --> Single final reduce (no intermediate levels)
  NO  --> Bin-pack outputs, run reduce level, repeat
```

The `directAgent` and `directTask` factories are optional. If not provided, the map phase
runs regardless of input size.

---

## 4. API Design

```java
// Generic type T: the type of each input item
MapReduceEnsemble.<OrderItem>builder()

    // -- Required: input items -------------------------------------------------
    .items(order.getItems())               // List<T>, must not be empty

    // -- Required: map phase factories -----------------------------------------
    .mapAgent(item -> Agent.builder()      // Function<T, Agent>
        .role(item.getDish() + " Chef")
        .goal("Prepare " + item.getDish())
        .llm(model)
        .build())
    .mapTask((item, agent) -> Task.builder() // BiFunction<T, Agent, Task>
        .description("Execute recipe for " + item.getDish())
        .expectedOutput("Recipe with steps and timing")
        .agent(agent)
        .outputType(DishResult.class)       // structured output works naturally
        .build())

    // -- Required: reduce phase factories -------------------------------------
    .reduceAgent(() -> Agent.builder()     // Supplier<Agent>
        .role("Sub-Chef")
        .goal("Consolidate preparations")
        .llm(model)
        .build())
    .reduceTask((agent, chunkTasks) ->     // BiFunction<Agent, List<Task>, Task>
        Task.builder()
            .description("Consolidate these preparations into a cohesive sub-plan")
            .expectedOutput("Consolidated plan")
            .agent(agent)
            .context(chunkTasks)           // user MUST wire context explicitly
            .build())

    // -- Reduction strategy (choose one) --------------------------------------
    .chunkSize(3)                          // Static: fixed groups of 3
    // OR:
    .targetTokenBudget(8_000)             // Adaptive: keep reducing until < 8K tokens
    // OR derive from model context window:
    .contextWindowSize(128_000)           // Adaptive: 128K * 0.5 = 64K budget
    .budgetRatio(0.5)                     // fraction of context window for reduce input

    // -- Optional: short-circuit (adaptive only) ------------------------------
    .directAgent(() -> Agent.builder()
        .role("Head Chef")
        .goal("Handle the entire order directly")
        .llm(model)
        .build())
    .directTask((agent, allItems) -> Task.builder()
        .description("Process all " + allItems.size() + " items directly")
        .expectedOutput("Complete plan")
        .agent(agent)
        .build())

    // -- Optional: safety limits (adaptive only) ------------------------------
    .maxReduceLevels(10)                  // prevent infinite reduction loops (default: 10)

    // -- Optional: Ensemble passthrough fields ---------------------------------
    .verbose(true)
    .listener(myListener)
    .captureMode(CaptureMode.STANDARD)
    .parallelErrorStrategy(ParallelErrorStrategy.FAIL_FAST)
    .costConfiguration(costs)
    .traceExporter(exporter)
    .toolExecutor(myExecutor)
    .toolMetrics(myMetrics)
    .input("key", "value")               // template variable inputs

    .build()
    .run();                              // returns EnsembleOutput
```

### Key design constraints

- `mapAgent`, `mapTask`, `reduceAgent`, `reduceTask` are all required.
- `chunkSize` and `targetTokenBudget` are **mutually exclusive**. Setting both throws
  `ValidationException` at `build()` time.
- When neither is set, the default is `chunkSize(5)` (static mode).
- `chunkSize` must be >= 2.
- `budgetRatio` must be in range `(0.0, 1.0]`. Default: `0.5`.
- `contextWindowSize` and `budgetRatio` together derive `targetTokenBudget`:
  `targetTokenBudget = (int)(contextWindowSize * budgetRatio)`.
- The reduce task factory receives the agent as its first argument AND must wire
  `.context(chunkTasks)` explicitly. The framework does not mutate the returned task.
  Failing to wire context produces a `ValidationException` when the inner `Ensemble`
  is validated.
- `directAgent`/`directTask` are only evaluated in adaptive mode. In static mode they
  are ignored.

---

## 5. Return Types

| Method | Returns | Description |
|---|---|---|
| `build()` | `MapReduceEnsemble<T>` | Configured instance ready to run |
| `run()` | `EnsembleOutput` | Execute and return aggregated results |
| `run(Map<String, String>)` | `EnsembleOutput` | Execute with runtime template variable overrides |
| `toEnsemble()` | `Ensemble` | Static mode only: returns the pre-built `Ensemble`. Throws `UnsupportedOperationException` in adaptive mode. |

---

## 6. Static DAG Construction Algorithm

Given N items and chunkSize K:

1. Create N map agents + N map tasks (no context, all independent).
2. Partition the map tasks into groups of at most K.
   - If N <= K: single group; go directly to step 4 (no intermediate levels).
   - If N > K: multiple groups at level 1.
3. For each group at level L:
   - Create one reduce agent (via `reduceAgent` supplier) per group.
   - Create one reduce task (via `reduceTask` factory) per group, receiving the group tasks
     as the `chunkTasks` argument.
   - Collect the reduce tasks as the "current level" output.
4. If the current level has more than K tasks, partition again and repeat from step 3
   with L = L+1.
5. When the current level has K or fewer tasks, create the final reduce task. This is the
   terminal node; its context wraps all current-level tasks.

**Tree depth:** O(log_K(N)). For N=100, K=5: depth = 3 levels.

**Example: N=7, K=3**

```
Map level:     [M1] [M2] [M3]   [M4] [M5] [M6]   [M7]
                     \               \
Reduce L1:       [R-L1-0]         [R-L1-1]       [R-L1-2 (single item)]
                     \               /                  /
Final reduce:              [Final]
```

Note: when the last group has only 1 item and the next level has <= K tasks, the framework
still creates a reduce task for the single-item group to keep the DAG shape consistent. The
reduce agent will effectively receive a single context item, which is valid.

---

## 7. Adaptive Execution Algorithm

```
INPUT: items, map factories, reduce factories, targetTokenBudget, maxReduceLevels

STEP 1: Input size estimation (short-circuit check)
  If directAgent and directTask are configured:
    estimated = sum(estimateTokens(itemDescription(item)) for item in items)
    If estimated <= targetTokenBudget:
      Run single direct task containing all items
      Return EnsembleOutput (single level)

STEP 2: Map phase
  Build map Ensemble with N independent tasks
  Run -> collect mapOutputs (list of TaskOutput)
  Accumulate trace and metrics

STEP 3: Check if single reduce is sufficient
  totalMapTokens = sum(tokenCount(output) for output in mapOutputs)
  If totalMapTokens <= targetTokenBudget:
    Build single final reduce Ensemble
    Run -> return EnsembleOutput (two levels: map + final reduce)

STEP 4: Adaptive reduce loop
  currentOutputs = mapOutputs
  reduceLevels = 0

  WHILE sum(tokenCount(output) for output in currentOutputs) > targetTokenBudget:
    If reduceLevels >= maxReduceLevels:
      Log warning: "maxReduceLevels reached; proceeding with final reduce anyway"
      Break

    bins = binPack(currentOutputs, targetTokenBudget)
    Build reduce Ensemble with one task per bin
    Run -> collect reduceOutputs
    Accumulate trace and metrics
    currentOutputs = reduceOutputs
    reduceLevels++

STEP 5: Final reduce
  Build final reduce Ensemble with single task
  context = all currentOutputs
  Run -> collect finalOutput
  Accumulate trace and metrics

STEP 6: Aggregate all traces and metrics into a single EnsembleOutput
  Return EnsembleOutput
```

---

## 8. Token Estimation

Token counts are needed to drive the adaptive reduction decision. The framework uses a
three-tier estimation strategy:

### 8.1 Post-execution (primary)

After each level runs, `TaskOutput.getMetrics().getOutputTokenCount()` provides the exact
output token count as reported by the LLM provider. This is the most accurate source and
is used when available (value != -1).

### 8.2 Heuristic fallback

When the provider returns -1 (token count unavailable), the framework estimates using:

```
estimatedTokens = rawOutput.length() / 4
```

This approximates the English-language average of ~4 characters per token. It is not precise
but is sufficient for bin-packing decisions. The fallback is logged at WARN level so users
are aware.

### 8.3 Custom estimator

Users can provide a `TokenEstimator` (a `Function<String, Integer>`) to override the default
heuristic:

```java
.tokenEstimator(text -> myTokenizer.count(text))
```

This is useful when using non-English models or when a provider-specific tokenizer is
available (e.g., `cl100k_base` for OpenAI models via `tiktoken4j`).

---

## 9. Bin-Packing Algorithm

The adaptive reduce loop groups outputs into bins where the total token count of each bin
does not exceed `targetTokenBudget`. The framework uses a **first-fit-decreasing (FFD)**
approximation:

1. Sort outputs by token count, descending.
2. For each output, assign it to the first existing bin that has capacity. If none, open a
   new bin.
3. A bin's capacity is `targetTokenBudget`. An output that exceeds `targetTokenBudget` on
   its own is placed in a bin by itself (cannot be sub-divided further without summarisation).

When an output exceeds `targetTokenBudget` on its own, the framework logs a WARNING:
```
MapReduce: single output from agent [{role}] exceeds targetTokenBudget ({actual} > {budget}).
Proceeding with a single-item reduce group. Consider increasing targetTokenBudget or using
outputType to produce more compact structured output.
```

---

## 10. Trace and Metrics Aggregation

The adaptive mode executes multiple `Ensemble.run()` calls. Each call produces its own
`EnsembleOutput` with its own `ExecutionTrace` and `ExecutionMetrics`. The framework
aggregates these into a single `EnsembleOutput` returned from `MapReduceEnsemble.run()`.

### Trace aggregation

A synthetic `ExecutionTrace` is produced with:
- A single `ensembleId` for the entire map-reduce run.
- `workflow` field: `"MAP_REDUCE_STATIC"` or `"MAP_REDUCE_ADAPTIVE"`.
- A new top-level field `mapReduceLevels`: list of level summaries (level index, workflow
  type per level, task count, duration).
- All `TaskTrace` objects from all levels, annotated with a `mapReduceLevel` field (int)
  and `nodeType` field (`"map"`, `"reduce"`, `"final-reduce"`, or `"direct"`).
- `startedAt` from the first map task, `completedAt` from the final reduce task.

### Metrics aggregation

`ExecutionMetrics` fields are summed across all levels:
- `totalTokens`, `totalInputTokens`, `totalOutputTokens`
- `totalLlmLatency`, `totalToolExecutionTime`
- `llmCallCount`, `toolCallCount`
- `totalCostEstimate` (when `CostConfiguration` is set)

---

## 11. Visualization Layer

### DagModel (devtools)

`DagTaskNode` gains a new optional field:

```java
public class DagTaskNode {
    // existing fields ...
    String nodeType;        // "map", "reduce", "final-reduce", "direct", or null for standard
    Integer mapReduceLevel; // 0 = map, 1+ = reduce levels; null for non-MapReduce tasks
}
```

`DagModel` gains:

```java
public class DagModel {
    // existing fields ...
    String mapReduceMode;   // "STATIC", "ADAPTIVE", or null for non-MapReduce ensembles
}
```

`DagExporter` is extended to export `MapReduceEnsemble` via `toEnsemble()` (static mode) or
from a post-execution DAG snapshot (adaptive mode).

`DagModel.schemaVersion` is bumped to `"1.1"` to reflect the new fields.

### agentensemble-viz (TypeScript)

`DagTaskNode` in `types/dag.ts` gains:

```typescript
nodeType?: 'map' | 'reduce' | 'final-reduce' | 'direct';
mapReduceLevel?: number;
```

`DagModel` in `types/dag.ts` gains:

```typescript
mapReduceMode?: 'STATIC' | 'ADAPTIVE';
```

`TaskNode.tsx` renders map and reduce nodes with visually distinct styling:
- Map nodes: standard task styling with a "MAP" badge.
- Reduce nodes: a distinct background colour with a "REDUCE L{level}" badge.
- Final-reduce nodes: an "AGGREGATE" badge.
- Direct nodes: a "DIRECT" badge.

The **Flow View** groups map nodes visually (same horizontal level) to communicate the
fan-out structure. Reduce levels are visually stacked below.

---

## 12. Error Handling

### Map phase errors

Errors in map tasks are governed by `parallelErrorStrategy` (passed through to the inner
`Ensemble`):
- `FAIL_FAST`: first failure aborts the map phase. `TaskExecutionException` propagates from
  `MapReduceEnsemble.run()`.
- `CONTINUE_ON_ERROR`: failed map tasks produce no output. The surviving outputs proceed to
  the reduce phase. If zero outputs survive, `ParallelExecutionException` propagates.

### Reduce phase errors

Same `parallelErrorStrategy` applies. A failing reduce task causes dependent downstream
reduce tasks to be skipped. If the final reduce task would have no context (all upstream
reduce tasks failed), a `TaskExecutionException` is thrown.

### Single output exceeds budget (adaptive)

Logged as a warning; the output is placed in a single-item bin and proceeds to the next
level. The framework never drops outputs silently.

### maxReduceLevels reached

If `maxReduceLevels` is exhausted before the total token count drops below budget, the
framework logs a warning and proceeds with the final reduce on whatever current outputs
remain. This prevents infinite loops at the cost of potentially large context in the final
reduce.

---

## 13. Validation Rules

At `MapReduceEnsemble.build()` time:
- `items` must not be null or empty.
- `mapAgent`, `mapTask`, `reduceAgent`, `reduceTask` must not be null.
- `chunkSize` (when set) must be >= 2.
- `chunkSize` and `targetTokenBudget` are mutually exclusive.
- `budgetRatio` must be in range `(0.0, 1.0]`.
- `maxReduceLevels` must be >= 1.
- `contextWindowSize` and `budgetRatio` must both be set if either is set (they derive
  `targetTokenBudget` together).

At runtime (when the inner `Ensemble` is built per level):
- The standard `EnsembleValidator` rules apply to each inner `Ensemble`.
- If the reduce task factory does not wire `context(chunkTasks)`, the inner `Ensemble`
  validation will throw `ValidationException` (circular context check or missing agent
  membership). The error message will reference the reduce task.

---

## 14. Edge Cases

| Scenario | Behaviour |
|---|---|
| N=1 item | Single map task + single final reduce. No intermediate levels. |
| N <= chunkSize (static) | Single map level + single final reduce. No intermediate levels. |
| All map outputs fit in budget (adaptive) | Single final reduce. No intermediate reduce levels. |
| Single map output exceeds budget | Placed in single-item bin. Warning logged. |
| Reduce output grows (e.g., verbose reduce prompt) | Next iteration's bin-packing handles it. `maxReduceLevels` prevents infinite loop. |
| All map tasks fail (FAIL_FAST) | `TaskExecutionException` propagates before reduce phase. |
| All map tasks fail (CONTINUE_ON_ERROR) | `ParallelExecutionException` propagates before reduce phase. |
| Some map tasks fail (CONTINUE_ON_ERROR) | Surviving outputs proceed to reduce. |
| Provider returns -1 for token counts | Heuristic fallback (`length / 4`). WARN logged. |
| directAgent configured but adaptive mode not enabled | `ValidationException` at build time (directAgent only valid with targetTokenBudget). |
| chunkSize=2, N=2 | Single reduce level: one group of 2 map tasks, final reduce. |

---

## 15. Full Code Examples

### Static Mode (Kitchen scenario)

```java
record DishResult(String dish, List<String> ingredients, int prepMinutes, String plating) {}
record MealPlan(List<DishResult> dishes, String servingOrder, String notes) {}

EnsembleOutput output = MapReduceEnsemble.<OrderItem>builder()
    .items(order.getItems())

    .mapAgent(item -> Agent.builder()
        .role(item.getDish() + " Chef")
        .goal("Prepare " + item.getDish() + " according to " + item.getCuisine() + " tradition")
        .background("Expert in " + item.getCuisine() + " cuisine.")
        .llm(model)
        .build())
    .mapTask((item, agent) -> Task.builder()
        .description("Prepare the recipe for " + item.getDish()
            + ". Dietary requirements: " + item.getDietaryNotes())
        .expectedOutput("Structured recipe result")
        .agent(agent)
        .outputType(DishResult.class)
        .build())

    .reduceAgent(() -> Agent.builder()
        .role("Sub-Chef")
        .goal("Consolidate dish preparations into a cohesive plan")
        .llm(model)
        .build())
    .reduceTask((agent, chunkTasks) -> Task.builder()
        .description("Consolidate these dish preparations. "
            + "Ensure timing, dietary restrictions, and plating work together.")
        .expectedOutput("Consolidated sub-plan with timing and coordination notes")
        .agent(agent)
        .context(chunkTasks)
        .build())

    .chunkSize(3)
    .verbose(true)
    .build()
    .run();

// Inspect the pre-built DAG
Ensemble innerEnsemble = MapReduceEnsemble.<OrderItem>builder()
    ...
    .chunkSize(3)
    .build()
    .toEnsemble();

DagExporter.export(innerEnsemble, Path.of("./traces/"));
```

### Adaptive Mode with Short-Circuit

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
        .context(chunkTasks)
        .build())

    // Short-circuit: if total input fits in budget, skip map-reduce entirely
    .directAgent(() -> Agent.builder()
        .role("Head Chef")
        .goal("Handle the entire order directly")
        .llm(model)
        .build())
    .directTask((agent, allItems) -> {
        String allDishes = allItems.stream()
            .map(OrderItem::getDish)
            .collect(Collectors.joining(", "));
        return Task.builder()
            .description("Plan the complete meal for: " + allDishes)
            .expectedOutput("Complete meal plan with all dishes")
            .agent(agent)
            .build();
    })

    .contextWindowSize(128_000)   // GPT-4o context window
    .budgetRatio(0.5)             // use up to 50% for context input
    .maxReduceLevels(5)
    .captureMode(CaptureMode.STANDARD)
    .build()
    .run();
```

---

## 16. Implementation Notes

### Package

`net.agentensemble.mapreduce` (new package in `agentensemble-core`).

### Class structure

```
MapReduceEnsemble<T>                 -- main builder + runner
MapReduceConfig<T>                   -- immutable config (extracted from builder)
MapReduceStaticExecutor<T>           -- builds static DAG, delegates to Ensemble.run()
MapReduceAdaptiveExecutor<T>         -- level-by-level execution loop
MapReduceTokenEstimator              -- token estimation (provider count, heuristic, custom)
MapReduceBinPacker                   -- first-fit-decreasing bin-packing
MapReduceTraceAggregator             -- combines ExecutionTraces across levels
MapReduceMetricsAggregator           -- sums ExecutionMetrics across levels
```

### Testing requirements

**Unit tests:**
- Builder validation: null items, null factories, chunkSize < 2, both strategies set,
  contextWindowSize without budgetRatio (and vice versa), directAgent with static mode.
- Static DAG construction: N=1, N=chunkSize, N=chunkSize+1, N=10/K=3 (verify tree depth),
  N=100/K=5 (verify tree depth = 3), context wiring correctness at each level.
- Bin-packing: standard case, single large item, all items equal, items with varied sizes.
- Token estimation: provider count available, provider count -1 (heuristic), custom estimator.

**Integration tests (mock LLMs):**
- Static end-to-end: 6 items, chunkSize=3 -- verify 6 map agents called, 2 L1 reduce agents,
  1 final reduce.
- Adaptive end-to-end: 6 items with known output sizes -- verify correct number of levels.
- Short-circuit: items with small total input -- verify single direct task, no map phase.
- Error propagation: map task failure with FAIL_FAST; map task failure with CONTINUE_ON_ERROR.

**Feature/E2E tests:**
- Full kitchen example from runnable example class.

---

## 17. Release Plan

`MapReduceEnsemble` is targeted for v2.0.0 and delivered in three issues:

| Issue | Scope | Depends On |
|---|---|---|
| Issue A | Static `MapReduceEnsemble` (`chunkSize`) | None |
| Issue B | Adaptive `MapReduceEnsemble` (`targetTokenBudget`) | Issue A |
| Issue C | Short-circuit optimization (`directAgent`/`directTask`) | Issue B |

Each issue includes full unit + integration tests, documentation updates (guide, example,
reference), and visualization layer changes. See the individual GitHub issues for detailed
acceptance criteria checklists.
