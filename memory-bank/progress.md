# Progress

## What Works (as of Issue #74 -- Tool Pipeline / Chaining)

**ToolPipeline (Issue #74):**
- `ToolPipeline.of(AgentTool...)` -- zero-config factory with auto-generated name and description
- `ToolPipeline.of(String, String, AgentTool...)` -- named factory
- `ToolPipeline.builder()` -- full builder with `.step()`, `.adapter()`, `.errorStrategy()`
- `PipelineErrorStrategy.FAIL_FAST` (default) -- stops on first failed step
- `PipelineErrorStrategy.CONTINUE_ON_FAILURE` -- forwards error message as next step's input
- Step adapters (`Function<ToolResult, String>`) -- reshape output between steps; access to `structuredOutput`
- `ToolContext` propagation -- metrics, logging, executor, and review handler forwarded to all nested `AbstractAgentTool` steps
- `getSteps()` and `getErrorStrategy()` public accessors
- Empty pipeline pass-through (returns input unchanged)
- Pipelines nest -- a pipeline can be a step inside another pipeline
- 49 unit tests + 7 integration tests; all pass; branch `feature/tool-pipeline-74`

## What Works (as of Issue #131 -- WebSocketStreamingListener v2.1.0)

**agentensemble-web module (Issues #130 + #131):**
- `WebDashboard.onPort(port)` -- zero-config factory; `WebDashboard.builder()` for full config
- `WebDashboard.start()` / `WebDashboard.stop()` -- lifecycle management; idempotent
- `WebDashboard.streamingListener()` -- returns `EnsembleListener` to wire into Ensemble
- `WebDashboard.reviewHandler()` -- returns `ReviewHandler` to wire into Ensemble
- `Ensemble.builder().webDashboard(dashboard)` -- one-line wiring (listener + review + lifecycle hooks)
- Embedded Javalin 6.3.0 WebSocket server at `ws://{host}:{port}/ws`
- HTTP endpoint `GET /api/status` returning `{"status":"running","clients":N,"port":P}`
- 15-second heartbeat broadcast; heartbeat `ScheduledFuture` cancelled on `stop()`
- Origin validation: loopback-only origins accepted when server bound to `localhost`/`127.0.0.1`/`::1`/`[::1]` (CSRF protection); URI-based host comparison prevents subdomain spoofing
- `WebSocketStreamingListener` broadcasts JSON messages for all 7 `EnsembleListener` event types; appends each to the late-join snapshot log
- `WebReviewHandler` gates execution via `CompletableFuture`; supports `CONTINUE`, `EXIT_EARLY`, and `FAIL` on timeout
- `ConnectionManager` tracks sessions, routes review decisions, resolves pending futures when last client disconnects
- Ensemble lifecycle messages: `ensemble_started` broadcast before first task; `ensemble_completed` broadcast after `Ensemble.run()` returns
- Late-join snapshot: all broadcast messages accumulated in `CopyOnWriteArrayList`; new clients receive `hello` with `ensembleId`, `startedAt`, and `snapshotTrace` (JSON array of all past events)
- `EnsembleDashboard` SPI: `onEnsembleStarted()` + `onEnsembleCompleted()` default no-op hooks
- `Ensemble.dashboard` field: calls lifecycle hooks from `runWithInputs()` before/after executor
- Wire protocol: 15+ `ServerMessage` subtypes + 2 `ClientMessage` subtypes with Jackson polymorphic dispatch
- `MessageSerializer` serializes/deserializes with `type` discriminator; `FAIL_ON_UNKNOWN_PROPERTIES=false`
- agentensemble-review promoted from `compileOnly` to `api` dependency in agentensemble-web
- 155+ tests across 7 test classes (including new `WebDashboardIntegrationTest`); all pass; JaCoCo LINE >= 90% and BRANCH >= 75% both pass
- Branch `feat/131-streaming-listener`; PR pending

## What Works (as of Issue #113 -- MapReduce task-first refactor)

**MapReduceEnsemble task-first API (Issue #113):**
- `MapReduceEnsemble.builder().mapTask(Function<T, Task>)` -- task-first map factory; agents synthesised automatically
- `MapReduceEnsemble.builder().reduceTask(Function<List<Task>, Task>)` -- task-first reduce factory
- `MapReduceEnsemble.builder().directTask(Function<List<T>, Task>)` -- task-first short-circuit factory
- `MapReduceEnsemble.builder().chatLanguageModel(ChatModel)` -- LLM for synthesis, passed to inner Ensembles
- `MapReduceEnsemble.of(model, items, mapDescription, reduceDescription)` -- zero-ceremony factory
- Mutual exclusivity validation: task-first and agent-first styles cannot be mixed per phase
- Backward compatibility: all existing agent-first API (`mapAgent`, `mapTask(BiFunction)`, `reduceAgent`, `reduceTask(BiFunction)`) unchanged
- `MapReduceAdaptiveExecutor` supports both styles; `chatLanguageModel` propagated to each inner Ensemble
- All v2.0.0 task features work in map/reduce tasks: tools, memory scopes, review gates, outputType
- 44 new tests (17 unit + 15 validation + 12 integration)
- `MapReduceTaskFirstKitchenExample.java` + `runMapReduceTaskFirstKitchen` Gradle task

## What Works (as of Issues #111 + #112)

- **EnsembleOutput partial results and workflow inference** (Issues #111, #112):
  - `ExitReason` enum: COMPLETED, USER_EXIT_EARLY, TIMEOUT, ERROR
  - `ReviewDecision.ExitEarly(boolean timedOut)` record -- `exitEarlyTimeout()` factory
  - `ExitEarlyException.isTimedOut()` -- TIMEOUT vs USER_EXIT_EARLY in all gate paths
  - `EnsembleOutput.isComplete()` -- true only when COMPLETED
  - `EnsembleOutput.completedTasks()` -- safe alias for getTaskOutputs()
  - `EnsembleOutput.lastCompletedOutput()` -- Optional<TaskOutput> last element
  - `EnsembleOutput.getOutput(Task task)` -- identity-based lookup; populated by executors
  - `Ensemble.workflow` nullable -- infers PARALLEL (context deps) or SEQUENTIAL (default)
  - `EnsembleValidator.resolveWorkflow()` -- same inference logic for validation
  - `ParallelWorkflowExecutor` + `ParallelTaskCoordinator` -- full exit-early support
  - `Ensemble.runWithInputs()` -- remaps agentResolved-keyed index back to original tasks

## What Works (as of Issues #106 + #107)

- **agentensemble-memory module** (Issue #106): all memory classes extracted from core
  into a dedicated module with SPI boundary. `MemoryRecord` carrier decouples `MemoryContext`
  from core's `TaskOutput`. `agentensemble-core` uses `compileOnly` dependency.

- **Task-scoped cross-execution memory** (Issue #107):
  - `MemoryStore` SPI with `inMemory()` and `embeddings(model, store)` factories
  - `InMemoryStore`: insertion-order, most-recent retrieval, eviction supported
  - `EmbeddingMemoryStore`: LangChain4j-backed semantic similarity, eviction is no-op
  - `MemoryScope.of(name)` and `MemoryScope.builder()` with eviction config
  - `EvictionPolicy.keepLastEntries(n)` and `keepEntriesWithin(duration)`
  - `MemoryTool.of(scope, store)` -- `@Tool storeMemory` and `@Tool retrieveMemory`
  - `MemoryEntry` updated to `{content, structuredContent, storedAt, metadata}` (v2.0.0 breaking)
  - `Task.builder().memory(String/String.../MemoryScope)` -- scope declaration
  - `Ensemble.builder().memoryStore(MemoryStore)` -- replaces old `memory(EnsembleMemory)`
  - Automatic scope read before task execution and write after completion
  - Scope isolation enforced by `AgentPromptBuilder` and `AgentExecutor`

## What Works (as of Issue #100)

- **MapReduceEnsemble short-circuit optimization** (`directAgent`/`directTask`): pre-execution
  input size estimation, bypass of map-reduce pipeline for small inputs, single direct task
  execution, trace with `nodeType = "direct"`, `mapReduceLevels` list with 1 entry,
  `inputEstimator` customization, inclusive boundary check, validation (mutual pairing,
  adaptive-only constraint)

- **Adaptive MapReduceEnsemble** (`targetTokenBudget`): level-by-level execution with
  first-fit-decreasing bin-packing, 3-tier token estimation, carrier task propagation,
  trace aggregation, CONTINUE_ON_ERROR partial failure recovery, post-execution DAG export

## What Works

### Homebrew Distribution (Issue #94)

**`agentensemble-viz` Homebrew tap (`agentensemble/tap`):**
- Self-contained native binary distributed via `brew install agentensemble/tap/agentensemble-viz`
- Bun `--compile` cross-compilation for darwin-arm64, darwin-x64, linux-x64 in CI
- Formula: `AgentEnsemble/homebrew-tap/Formula/agentensemble-viz.rb` with platform-aware
  `on_macos`/`on_linux` blocks, comment-anchored SHA256 values
- Auto-update workflow: `AgentEnsemble/homebrew-tap/.github/workflows/update-formula.yml`
  triggered by `repository_dispatch` from main repo; zero manual steps

**`cli.js` adaptations for Bun compile:**
- `distDir` uses `new URL('./dist/', import.meta.url).pathname` (Bun embeds dist)
- Package version read via `readFileSync(new URL('./package.json', import.meta.url))` (Bun embeds pkg)
- `--version` flag: prints `agentensemble-viz/<version>`, exits 0

**Release pipeline (`release.yml`):**
- Node 20 + Bun setup after Java artifact upload
- `npm ci && npm run build` in agentensemble-viz/
- Three sequential Bun cross-compiles -> tarballs -> upload to GitHub Release
- `curl` `repository_dispatch` to `AgentEnsemble/homebrew-tap` with version payload

**Tests:**
- 4 new CLI integration tests (45 total passing): `--version` exit code, output format,
  no-server-start, clean stderr; `// @vitest-environment node` + stripped `NODE_OPTIONS`

### Visualization Tooling (Issue #44)

**`agentensemble-devtools` Java module:**
- `DagExporter.build(Ensemble)` -- computes topological levels + critical path from ensemble config
- `EnsembleDevTools.buildDag()` / `exportDag()` / `exportTrace()` / `export()` facade
- `DagModel` JSON export with schema versioning (`type: "dag"`)
- 29 unit tests across `DagExporterTest` and `EnsembleDevToolsTest`

**`agentensemble-viz` npm package:**
- CLI: `npx @agentensemble/viz ./traces/` starts local HTTP server, auto-opens browser
- Flow View: ReactFlow DAG with dagre layout, agent color coding, critical path highlighting
- Timeline View: SVG Gantt chart with agent swimlanes, LLM sub-bars, tool call markers
- Detail panels for tasks, LLM calls, and tool invocations
- CLI server API: `/api/files` (list) + `/api/file` (serve) for auto-loading
- Dark/light mode toggle
- TypeScript types matching ExecutionTrace and DagModel JSON schemas
- 41 unit tests (parser, colors utilities)

### CaptureMode (Issue #89)
- `CaptureMode` enum (OFF/STANDARD/FULL) with `isAtLeast()` and `CaptureMode.resolve()`
  supporting JVM system property `agentensemble.captureMode` and env var
  `AGENTENSEMBLE_CAPTURE_MODE` for zero-code activation
- `CapturedMessage` value object: converts LangChain4j `ChatMessage` subtypes to serializable form
- `MemoryOperationListener` interface wired into `MemoryContext`
- `LlmInteraction.messages` field: full per-iteration message history at STANDARD+
- `ToolCallTrace.parsedInput` field: structured tool arguments map at FULL
- `ExecutionTrace.captureMode` field; schema version bumped to 1.1
- `Ensemble.captureMode` builder field with effective-mode resolution and FULL auto-export

### Execution Metrics and Observability (Issue #42)
- `TaskMetrics` on `TaskOutput`: token counts (input/output/total with -1 unknown), LLM latency,
  tool execution time, prompt build time, delegation count, memory operation counts, cost estimate
- `ExecutionMetrics` on `EnsembleOutput`: aggregated totals; `from(List<TaskOutput>)` factory
- `CostConfiguration` + `CostEstimate`: optional cost estimation from token counts
- `MemoryOperationCounts`: STM/LTM/entity counters with `add()`
- `ExecutionTrace` + `TaskTrace` + `LlmInteraction` + `ToolCallTrace` + `DelegationTrace`:
  full hierarchical call trace per run
- `ExecutionTrace.toJson()` / `toJson(Path)`: pretty JSON with ISO-8601 timestamps/durations
- `ExecutionTraceExporter` + `JsonTraceExporter`: pluggable export, auto-export via `traceExporter`

### Core Framework (agentensemble-core)
- Sequential, parallel (dependency-based DAG), and hierarchical workflows
- Agent execution with ReAct-style tool-calling loop
- Multi-tool parallel execution via Java 21 virtual threads (single-turn parallelism)
- Configurable tool executor and metrics backend on `Ensemble.builder()`
- `AbstractAgentTool` base class with template method, auto-metrics, structured logging, exception safety
- `ToolContext` (Logger + ToolMetrics + Executor) injected into `AbstractAgentTool` instances
- `ToolMetrics` interface + `NoOpToolMetrics` default
- `ToolContextInjector` friend-bridge for cross-package injection
- Enhanced `ToolResult` with optional `structuredOutput` field
- Enhanced `ToolCallEvent` with `structuredResult` and `(toolName, agentRole)` tagging
- Short-term, long-term, and entity memory (EnsembleMemory)
- Agent delegation (peer-to-peer with depth limiting)
- Input/output guardrails
- Structured output parsing with retry logic
- Template variable substitution in task descriptions
- Callback/listener system (onTaskStart/Complete/Failed/onToolCall/onDelegationStarted/Completed/Failed)
- Verbose logging mode
- MDC propagation for structured logging in parallel workflows
- Hierarchical constraints (HierarchicalConstraints, ConstraintViolationException)
- Delegation policy hooks (DelegationPolicy, DelegationPolicyResult, DelegationPolicyContext)
- Delegation lifecycle events (DelegationStartedEvent, DelegationCompletedEvent, DelegationFailedEvent)
- Manager prompt extension hook (ManagerPromptStrategy, ManagerPromptContext)
- Structured delegation contracts (DelegationRequest, DelegationResponse)

### Built-in Tool Library
Per-tool Gradle modules under `agentensemble-tools/`:

| Module | Package | Status |
|--------|---------|--------|
| `agentensemble-tools-calculator` | `net.agentensemble.tools.calculator` | Done |
| `agentensemble-tools-datetime` | `net.agentensemble.tools.datetime` | Done |
| `agentensemble-tools-json-parser` | `net.agentensemble.tools.json` | Done |
| `agentensemble-tools-file-read` | `net.agentensemble.tools.io` | Done |
| `agentensemble-tools-file-write` | `net.agentensemble.tools.io` | Done |
| `agentensemble-tools-web-search` | `net.agentensemble.tools.web.search` | Done |
| `agentensemble-tools-web-scraper` | `net.agentensemble.tools.web.scraper` | Done |
| `agentensemble-tools-process` | `net.agentensemble.tools.process` | Done |
| `agentensemble-tools-http` | `net.agentensemble.tools.http` | Done |
| `agentensemble-tools-bom` | (platform) | Done |

All tools extend `AbstractAgentTool` with automatic metrics, logging, exception handling.

### Metrics Integration
- `agentensemble-metrics-micrometer` with `MicrometerToolMetrics`
- `agentensemble.tool.executions` counter + `agentensemble.tool.duration` timer
- Tagged by `(tool_name, agent_role, outcome)`

### Build Infrastructure
- `buildSrc/agentensemble.tool-conventions` precompiled convention plugin
- `pluginManagement` in `settings.gradle.kts` for plugin version alignment
- JaCoCo coverage enforced: LINE >= 90%, BRANCH >= 75% across all modules
- Spotless + ErrorProne enabled on all modules

### Documentation
- Complete API docs for all framework classes
- Visualization guide: `docs/guides/visualization.md`
- Visualization example: `docs/examples/visualization.md`
- All other guides, examples, references up to date
- `mkdocs.yml` navigation updated with visualization pages

### MapReduceEnsemble Static Strategy (Issue #98 -- v2.0.0)

- `MapReduceEnsemble<T>` in `net.agentensemble.mapreduce`
- Builder with full validation (items, factories, chunkSize >= 2)
- Static DAG construction: O(log_K(N)) tree depth, correct context wiring
- `toEnsemble()` for devtools inspection
- `run()` / `run(Map)` execute via inner Ensemble.PARALLEL
- `DagTaskNode.nodeType` + `DagTaskNode.mapReduceLevel` (devtools)
- `DagModel.mapReduceMode` + `schemaVersion = "1.1"` (devtools)
- `DagExporter.build(MapReduceEnsemble<?>)` enriched overload
- TypeScript types updated + TaskNode.tsx MAP/REDUCE/AGGREGATE badges
- 35 unit tests + 13 integration tests; all passing
- `MapReduceKitchenExample.java` with structured output
- Full documentation: guide, example walkthrough, reference, README

## What's Left to Build

### v2.0.0 -- Task-First Architecture (design complete, implementation pending)

The full architecture is documented in `docs/design/15-v2-architecture.md`.
GitHub issues created to track implementation (see activeContext.md for issue numbers).

Implementation workstreams (can run in parallel once SPI contracts are agreed):
- **Group A** (core): Task absorbs Agent responsibilities; `AgentSynthesizer` SPI
- **Group B** (memory): `agentensemble-memory` module; task-scoped named memory scopes; cross-run persistence
- **Group C** (review): `agentensemble-review` module; `ReviewHandler` SPI; `ConsoleReviewHandler`; before/during/after gates
- **Group D** (output/workflow): `EnsembleOutput` partial-results redesign; workflow inference from context declarations
- **Group E** (MapReduce refactor): rework after #98-100 land and Group A completes
- **Group F** (finalization): `agentensemble-bom`; `docs/migration/v1-to-v2.md`; updated examples

### v2.1.0 -- MapReduceEnsemble Adaptive (Issue #99)

- Adaptive reduction with `targetTokenBudget` (level-by-level, token-driven)
- `contextWindowSize` + `budgetRatio` derive `targetTokenBudget`
- Bin-packing (first-fit-decreasing) algorithm
- Token estimation: provider count, heuristic (length/4), custom estimator
- Trace and metrics aggregation across multiple Ensemble.run() calls

### v2.2.0 -- MapReduceEnsemble Short-Circuit (Issue #100) -- COMPLETE

- `directAgent` / `directTask` for skipping map-reduce when input is small
- Only for adaptive mode
- Implemented in `agentensemble-core` and documented

### v2.1.0 -- Live Execution Dashboard (Groups G + H1 complete)

Design document: `docs/design/16-live-dashboard.md`

**Complete (PR #138 open):**
- Group G1: `agentensemble-web` module with embedded Javalin WebSocket server
- Group G2: `WebSocketStreamingListener` -- all 7 `EnsembleListener` event types
- Group H1: `WebReviewHandler` -- real browser-based review implementation (replaces stub)

**Remaining:**
- Group H2: Viz review approval UI (modal with Approve/Edit/Exit Early + countdown timer)
- Group I1: agentensemble-viz WebSocket client + `/live` route + incremental `liveReducer` state machine
- Group I2: Live timeline/flow updates

### Near-term (follow-up issues)
- MCP (Model Context Protocol) integration (`McpAgentTool`)
- GraalVM polyglot tool support
- Tool output validation / schema enforcement

### Longer-term
- See `design/13-future-roadmap.md` for the full roadmap

## Current Status

**Issues #111 + #112** implementation complete on branch `feat/111-112-partial-results-workflow-inference`:
- `agentensemble-core` -- 1162+ tests pass; 0 failures; full Gradle build successful
- `agentensemble-review` -- all tests pass; javadoc clean; spotless clean
- New test files: `PartialResultsIntegrationTest` (8 tests), `WorkflowInferenceIntegrationTest` (7 tests)
- Updated: `EnsembleOutputTest` (expanded), `EnsembleTest` (workflow null default), `EnsembleValidationTest`

v2.0.0 implementation status:
- Group A (Task-First, AgentSynthesizer): COMPLETE (#104, #105)
- Group B (agentensemble-memory, scoped memory): COMPLETE (#106, #107)
- Group C (agentensemble-review, review gates): COMPLETE (#108, #109, #110)
- Group D (partial results, workflow inference): COMPLETE (#111, #112)
- Group E (MapReduce refactor): COMPLETE (#113, branch feat/113-mapreduce-task-first)
- Group F (BOM, migration guide, examples): COMPLETE (#114, #115, branch feat/bom-and-migration-guide)
- Issue #126 (Tool-level approval gates): COMPLETE (branch feat/126-tool-level-approval-gates)

## Known Issues

None at this time.

## Evolution of Project Decisions

- **Async**: Decided NOT to add `AsyncAgentTool` interface. Java 21 virtual threads make
  blocking cheap; the framework parallelizes multi-tool turns using `CompletableFuture`
  internally. Tool authors write plain synchronous code.
- **Module layout**: Per-tool modules (not per-category) chosen from the start to avoid
  breaking changes later and to give users precise dependency control.
- **Metrics tagging**: Both `tool_name` and `agent_role` tags required after design discussion
  -- same tool used by different agents should be distinguishable in metrics.
- **Devtools as separate module**: `EnsembleDevTools` / `DagExporter` placed in
  `agentensemble-devtools` rather than `agentensemble-core` so core stays lean for production;
  users add devtools with `testImplementation` or `runtimeOnly` scope.
- **Viz as npm package**: The trace viewer is a standalone TypeScript/React app distributed via
  npm (`npx @agentensemble/viz`). Java code exports JSON files; the viewer reads them. This
  separates the visualization concern from the Java library entirely.
