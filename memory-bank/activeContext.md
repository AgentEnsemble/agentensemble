# Active Context

## Current Work Focus

Issue #98 (Static MapReduceEnsemble with chunkSize) is complete on branch
`feat/issue-98-static-map-reduce-ensemble`. All acceptance criteria satisfied.

## Recent Changes

### Issue #98 -- Static MapReduceEnsemble with chunkSize (v2.0.0)

**New class: `net.agentensemble.mapreduce.MapReduceEnsemble<T>`**

- Builder with required fields: `items`, `mapAgent`, `mapTask`, `reduceAgent`, `reduceTask`
- `chunkSize` with default 5, validated >= 2
- All Ensemble passthrough fields: `verbose`, `listener(s)`, `captureMode`,
  `parallelErrorStrategy`, `costConfiguration`, `traceExporter`, `toolExecutor`,
  `toolMetrics`, `input`/`inputs`
- Static DAG construction algorithm: O(log_K(N)) tree depth
  - N <= K: N map tasks + 1 final reduce (no intermediate level)
  - N > K: intermediate reduce levels until level size <= K, then final reduce
- `toEnsemble()` returns pre-built inner `Ensemble` (Workflow.PARALLEL)
- `run()` / `run(Map)` delegate to inner Ensemble
- `getNodeTypes()` / `getMapReduceLevels()` for devtools enrichment

**Tests added:**
- `MapReduceEnsembleTest` (35 unit tests): builder validation, DAG construction
  for N=1/3/4/9/25/26 with K=3/5, context wiring, agent counts, factory
  distinct-instance guarantees, all optional builder setter coverage
- `MapReduceEnsembleIntegrationTest` (13 integration tests): N=6/K=3 end-to-end,
  FAIL_FAST, CONTINUE_ON_ERROR, runtime input overrides, metadata accessors

**agentensemble-devtools changes:**
- `DagTaskNode`: added `nodeType` (`"map"`, `"reduce"`, `"final-reduce"`) and
  `mapReduceLevel` (int) optional fields
- `DagModel`: added `mapReduceMode` (`"STATIC"`, `"ADAPTIVE"`) optional field;
  `schemaVersion` bumped from `"1.0"` to `"1.1"`
- `DagExporter.build(MapReduceEnsemble<?>)`: new overload that enriches task nodes
  with map-reduce metadata and sets `mapReduceMode = "STATIC"`
- `DagExporterTest`: 11 new tests for the MapReduceEnsemble overload

**agentensemble-viz changes:**
- `types/dag.ts`: `nodeType?` and `mapReduceLevel?` on `DagTaskNode`;
  `mapReduceMode?` on `DagModel`; schema version docs updated to 1.1
- `TaskNode.tsx`: renders MAP, REDUCE Ln, AGGREGATE, DIRECT badges for
  map-reduce node types via conditional rendering

**Example:**
- `MapReduceKitchenExample.java`: 7-dish restaurant order, chunkSize=3,
  structured output via `DishResult` record
- `runMapReduceKitchen` Gradle task in `agentensemble-examples/build.gradle.kts`

**Documentation:**
- `docs/guides/map-reduce.md`: comprehensive guide (algorithm, chunkSize tuning,
  wiring context, structured output, error handling, devtools, comparison table)
- `docs/examples/map-reduce.md`: kitchen example walkthrough
- `docs/reference/ensemble-configuration.md`: MapReduceEnsemble builder tables
- `mkdocs.yml`: guide and example pages in nav
- `README.md`: MapReduceEnsemble section, `runMapReduceKitchen` in examples,
  v2.0.0 in roadmap, `14-map-reduce.md` in design docs

## Next Steps

- PR for issue #98 -- close out the issue on GitHub
- Issue #99 -- Adaptive MapReduceEnsemble with `targetTokenBudget` (v2.1.0)
- Issue #100 -- MapReduceEnsemble short-circuit optimization (v2.2.0)
- See `design/13-future-roadmap.md` and `design/14-map-reduce.md` for next phases
