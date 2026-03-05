# Active Context

## Current Work Focus

Issue #99 (Adaptive MapReduceEnsemble with targetTokenBudget) is complete on branch
`feat/issue-99-adaptive-map-reduce-ensemble`.

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
- Integration: `MapReduceEnsembleAdaptiveRunTest` (9) using mock ChatModels
- Devtools: `DagExporterTest` + 8 new `build(ExecutionTrace)` tests

## Next Steps

- PR and close issue #99
- Issue #100: Short-circuit optimization (`directAgent`/`directTask`) - v2.1.0
  Depends on issue #99 (this work)

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

## Active Branch

`feat/issue-99-adaptive-map-reduce-ensemble`
