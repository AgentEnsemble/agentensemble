# Active Context

## Current Work Focus

Issue #44 (Interactive execution graph visualization) has been implemented, incorporating the
work from issues #42 (Execution metrics) and #89 (CaptureMode) which were already completed.
Two new modules were created: `agentensemble-devtools` (Java) and `agentensemble-viz` (TypeScript).

## Recent Changes

### Issue #44 -- Interactive Execution Graph Visualization

**New Java module: `agentensemble-devtools`**

`net.agentensemble.devtools` package:

- **`DagAgentNode`** (`@Value @Builder`) -- agent configuration snapshot (role, goal, background,
  toolNames, allowDelegation)
- **`DagTaskNode`** (`@Value @Builder`) -- task node with: id (index-based), description,
  expectedOutput, agentRole, dependsOn (List<String>), parallelGroup (topological level),
  onCriticalPath (boolean)
- **`DagModel`** (`@Value @Builder`) -- complete pre-execution DAG snapshot; `schemaVersion="1.0"`,
  `type="dag"`, workflow, generatedAt, agents, tasks, parallelGroups (List<List<String>>),
  criticalPath; `toJson()` / `toJson(Path)` methods via Jackson
- **`DagExporter`** -- static `build(Ensemble)` method: builds `TaskDependencyGraph`, computes
  topological levels via memoized recursion, computes parallel groups, computes critical path
  via endpoint backtracking, serializes to `DagModel`
- **`EnsembleDevTools`** -- facade with static methods:
  - `buildDag(Ensemble)` -- returns `DagModel` without writing any file
  - `exportDag(Ensemble, Path)` -- writes `ensemble-dag-<timestamp>.dag.json`, returns path
  - `exportTrace(EnsembleOutput, Path)` -- writes `ensemble-trace-<timestamp>.trace.json`, returns path
  - `export(Ensemble, EnsembleOutput, Path)` -- exports both, returns `ExportResult` record

**Tests (all pass, coverage >= 90%):**
- `DagExporterTest` (17 tests): null/empty validation, single task, linear chain, fan-out,
  diamond, two independent roots, workflow preservation, agent node inclusion, JSON round-trip
- `EnsembleDevToolsTest` (12 tests): all facade methods including null handling, file creation,
  directory creation, JSON content validation

**New TypeScript module: `agentensemble-viz`**

Located at `agentensemble-viz/` in the project root (npm package, not a Gradle module).

Key files:
- `package.json` -- `@agentensemble/viz` npm package; bin: `agentensemble-viz`
- `cli.js` -- Node.js CLI server; serves static app + `/api/files` and `/api/file` endpoints
- `src/types/trace.ts` -- TypeScript types for ExecutionTrace JSON (schema version 1.1)
- `src/types/dag.ts` -- TypeScript types for DagModel JSON (schema version 1.0)
- `src/utils/parser.ts` -- file parsing, type detection, duration/token formatting
- `src/utils/colors.ts` -- agent color palette, tool outcome colors, opacity utilities
- `src/utils/graphLayout.ts` -- dagre-based DAG layout for ReactFlow
- `src/pages/LoadTrace.tsx` -- landing page (CLI server file list + drag-and-drop)
- `src/pages/FlowView.tsx` -- DAG visualization with ReactFlow + dagre layout
- `src/pages/TimelineView.tsx` -- SVG Gantt timeline with agent swimlanes
- `src/components/graph/TaskNode.tsx` -- custom ReactFlow node
- `src/components/shared/DetailPanel.tsx` -- flow view detail panel
- `src/components/shared/MetricsBadge.tsx` -- metrics display badges

**Tests (41/41 pass):**
- `src/__tests__/parser.test.ts` -- 28 tests: file detection, parsing, duration, formatting
- `src/__tests__/colors.test.ts` -- 13 tests: color assignment, seeding, opacity, outcomes

**Documentation:**
- `docs/guides/visualization.md` -- new guide
- `docs/examples/visualization.md` -- new example
- `mkdocs.yml` -- both pages added to nav

**GitHub issue:**
- #94 created: "Distribute agentensemble-viz via Homebrew tap" (follow-up for future)

## Next Steps

- PR for issue #44 -- close out the issue
- Issue #94 (Homebrew tap distribution) for future
- See `design/13-future-roadmap.md` for overall roadmap
