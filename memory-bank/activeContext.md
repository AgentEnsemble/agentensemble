# Active Context

## Current Work Focus

Issue #94 (Distribute agentensemble-viz via Homebrew tap) has been implemented. The
`agentensemble-viz` CLI is now distributed as a self-contained binary via a Homebrew tap
(`agentensemble/tap/agentensemble-viz`) in addition to the existing npm/npx path. Releases
are fully automated: tag push -> Bun cross-compile -> tarball upload -> formula update with
zero manual steps.

## Recent Changes

### Issue #94 -- Homebrew Tap Distribution

**`agentensemble-viz/cli.js` changes:**
- Replaced `__dirname`-based `distDir` with `new URL('./dist/', import.meta.url).pathname` so
  Bun embeds the dist directory into the compiled binary
- Added version reading via `readFileSync(new URL('./package.json', import.meta.url).pathname)`
  using the same URL pattern so Bun embeds package.json too
- Added `--version` flag handler that prints `agentensemble-viz/<version>` and exits 0

**`agentensemble-viz/package.json` changes:**
- Added three compile scripts: `compile:darwin-arm64`, `compile:darwin-x64`, `compile:linux-x64`
  using `bun build --compile --target=<target>`

**`.github/workflows/release.yml` changes:**
- Added Node.js setup, Bun setup (oven-sh/setup-bun@v2), viz build, cross-compilation for 3
  platforms, tarball creation, upload to GitHub Release, and `repository_dispatch` to
  homebrew-tap -- all in sequence after the Java artifacts are uploaded

**`AgentEnsemble/homebrew-tap` repo:**
- `Formula/agentensemble-viz.rb`: platform-aware formula with `on_macos`/`on_linux` blocks,
  comment-anchored SHA256 values for automated updates
- `.github/workflows/update-formula.yml`: triggered by `repository_dispatch` event from the
  main repo; downloads tarballs, computes SHA256, updates formula, commits and pushes

**Tests:**
- `agentensemble-viz/src/__tests__/cli.test.ts`: 4 new integration tests for the `--version`
  flag (exit code, output format, no server start, clean stderr); uses `// @vitest-environment node`
  pragma and strips `NODE_OPTIONS` from child process env to avoid vitest interference

**Documentation:**
- `docs/guides/visualization.md`: Homebrew listed as Option 1 (recommended for regular use)
- `docs/examples/visualization.md`: Homebrew option added to "Running the Viewer" section
- `docs/getting-started/installation.md`: new "Execution Graph Visualizer" section with all
  three install options (Homebrew, npx, global npm)
- `README.md`: new "Execution Graph Visualization" section with Homebrew + npx install
  commands; docs table updated

## Next Steps (after #94 PR merges)

- Secret `ORG_HOMEBREW_TAP_TOKEN` must be added to the main repo's GitHub Secrets (PAT with
  `repo` scope on `AgentEnsemble/homebrew-tap`)
- Issue #44 PR -- close out the visualization issue
- MCP (Model Context Protocol) integration (`McpAgentTool`)
- See `design/13-future-roadmap.md` for overall roadmap

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
