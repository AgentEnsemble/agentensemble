# Progress

## What Works

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

## What's Left to Build

### Near-term (follow-up issues)
- Issue #94: Distribute `agentensemble-viz` via Homebrew tap
- MCP (Model Context Protocol) integration (`McpAgentTool`)
- GraalVM polyglot tool support
- Tool output validation / schema enforcement

### Longer-term
- See `design/13-future-roadmap.md` for the full roadmap

## Current Status

**Issue #44** implementation complete:
- `agentensemble-devtools` module: 29/29 tests pass, BUILD SUCCESSFUL
- `agentensemble-viz` npm: 41/41 tests pass, clean TypeScript build
- Full Gradle build: 159 actionable tasks, BUILD SUCCESSFUL

All tests pass:
- `agentensemble-core` -- check + javadoc passes
- `agentensemble-devtools` -- check + javadoc passes (29 tests)
- All 9 `agentensemble-tools-*` modules -- check passes
- `agentensemble-metrics-micrometer` -- check passes
- `agentensemble-examples` -- compiles successfully
- `agentensemble-viz` -- 41 TypeScript tests pass, production build clean

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
