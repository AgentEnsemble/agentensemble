# Active Context

## Current Work Focus

Issue #100 (MapReduceEnsemble short-circuit optimization) is complete.
Issue #99 (Adaptive MapReduceEnsemble) was previously completed.

## Recent Changes

### Issue #99: Adaptive MapReduceEnsemble

Extends `MapReduceEnsemble<T>` with an adaptive strategy that drives tree reduction based on
actual output token counts at runtime.

**New classes in `agentensemble-core`:**
- `MapReduceTokenEstimator` - 3-tier token estimation (provider count -> custom function -> heuristic)
- `MapReduceBinPacker` - first-fit-decreasing bin-packing algorithm
- `MapReduceAdaptiveExecutor<T>` - level-by-level adaptive execution engine
- `PassthroughChatModel` - returns fixed text; used for carrier tasks between adaptive levels
- `MapReduceLevelSummary` - per-level summary in `ExecutionTrace`

**Modified classes:**
- `MapReduceEnsemble` - new builder fields (`targetTokenBudget`, `contextWindowSize`,
  `budgetRatio`, `maxReduceLevels`, `tokenEstimator`), adaptive/static dispatch,
  `isAdaptiveMode()`, `toEnsemble()` throws in adaptive mode
- `TaskTrace` - added `nodeType` and `mapReduceLevel` fields
- `ExecutionTrace` - added `mapReduceLevels` list (List<MapReduceLevelSummary>)
- `DagExporter` - added `build(ExecutionTrace)` for post-execution adaptive DAG export

**Key design decision:** Carrier tasks backed by `PassthroughChatModel` propagate context
from one adaptive level to the next. Carrier task traces and outputs are filtered from the
aggregated `EnsembleOutput` and `ExecutionTrace`.

**Tests added:**
- Unit: `MapReduceTokenEstimatorTest` (12), `MapReduceBinPackerTest` (13),
  `MapReduceEnsembleAdaptiveValidationTest` (22)
- Integration: `MapReduceEnsembleAdaptiveRunTest` (10) using mock ChatModels
- Devtools: `DagExporterTest` + 8 new `build(ExecutionTrace)` tests

### v2.0.0 Architecture Design (branch: v2-architecture-design)

- `docs/design/15-v2-architecture.md`: full design document covering all v2.0.0
  architectural decisions
- Task-First API, task-scoped cross-execution memory, human-in-the-loop review gates,
  partial results redesign
- SPI contracts: `AgentSynthesizer`, `MemoryStore`, `ReviewHandler`

## Next Steps

- Issue #100: DONE -- short-circuit optimization (`directAgent`/`directTask`)
- Begin v2.0.0 workstreams (Groups A-F per design doc)

## Important Patterns and Preferences

- Static MapReduce: single `Ensemble.run()` with pre-built DAG, `toEnsemble()` works
- Adaptive MapReduce: multiple `Ensemble.run()` calls, one per level; carrier tasks
  bridge context between levels; `toEnsemble()` throws `UnsupportedOperationException`
- `CONTINUE_ON_ERROR` with partial map failures: `ParallelExecutionException` carries
  surviving `completedTaskOutputs`; adaptive executor extracts these to proceed
- Trace aggregation: all traces from all levels combined into single `ExecutionTrace`
  with `workflow = "MAP_REDUCE_ADAPTIVE"` and per-level summaries in `mapReduceLevels`
- Post-execution DAG export: `DagExporter.build(ExecutionTrace)` for adaptive runs
- Carrier tasks use `__carry__:` role prefix for filtering from aggregated output

## Recent Changes

### Issue #100: MapReduceEnsemble Short-Circuit Optimization

Extends `MapReduceEnsemble<T>` adaptive mode with a pre-execution short-circuit. When
the total estimated input size fits within `targetTokenBudget` and `directAgent`/`directTask`
are configured, the entire map-reduce pipeline is bypassed in favour of a single direct task.

**Modified classes in `agentensemble-core`:**
- `MapReduceEnsemble` -- 3 new builder fields (`directAgent`, `directTask`, `inputEstimator`),
  `NODE_TYPE_DIRECT = "direct"` constant, `validateDirectFields()` validation method
- `MapReduceAdaptiveExecutor<T>` -- `estimateInputTokens()` pre-execution check,
  `runDirectPhase()` runner, short-circuit guard in `run()` before map phase

**New test files (all passing):**
- `MapReduceEnsembleShortCircuitValidationTest` (9 tests) -- builder validation unit tests
- `MapReduceEnsembleShortCircuitRunTest` (16 tests) -- execution unit tests with mock LLMs
- `MapReduceEnsembleShortCircuitIntegrationTest` (7 tests) -- integration tests with Mockito

**Modified test files:**
- `DagExporterTest` -- 5 new short-circuit visualization tests

**Documentation updated:**
- `docs/reference/ensemble-configuration.md` -- `directAgent`, `directTask`, `inputEstimator` fields
- `docs/guides/map-reduce.md` -- Short-circuit optimization section
- `docs/examples/map-reduce.md` -- Short-circuit example section

**Key design decisions:**
- Short-circuit is opt-in: no `directAgent`/`directTask` = no check (backwards-compatible)
- Input estimation heuristic: `text.length() / 4` (same as output heuristic in #99)
- Boundary inclusive: fires when `estimated <= targetTokenBudget`
- Static mode forbidden: `ValidationException` if `directAgent`/`directTask` set with `chunkSize`
- Trace: `workflow = "MAP_REDUCE_ADAPTIVE"`, single `TaskTrace` with `nodeType = "direct"`,
  `mapReduceLevel = 0`, `mapReduceLevels` list has exactly 1 entry
- `TaskNode.tsx` already had the DIRECT badge -- no viz changes needed

## Important Patterns and Preferences

- Short-circuit check: `estimateInputTokens()` uses `inputEstimatorFn` (or `toString()`) then `length/4`
- Short-circuit fires: `directAgentFactory != null && directTaskFactory != null && estimated <= budget`
- Direct phase: single `Ensemble.run()` with one agent + one task; trace annotated from raw trace
- `runDirectPhase()` builds `ExecutionTrace` directly (like `aggregate()` does) with `WORKFLOW_ADAPTIVE`
- Static MapReduce: single `Ensemble.run()` with pre-built DAG, `toEnsemble()` works
- Adaptive MapReduce: multiple `Ensemble.run()` calls, one per level; carrier tasks
  bridge context between levels; `toEnsemble()` throws `UnsupportedOperationException`
- `CONTINUE_ON_ERROR` with partial map failures: `ParallelExecutionException` carries
  surviving `completedTaskOutputs`; adaptive executor extracts these to proceed
- Carrier tasks use `__carry__:` role prefix for filtering from aggregated output
- Post-execution DAG export: `DagExporter.build(ExecutionTrace)` for adaptive runs
