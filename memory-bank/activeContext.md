# Active Context

## Current Work Focus

Feature branch `feature/60-built-in-tool-library` implemented Issues #60 and #73
(Enhanced Tool Model: AbstractAgentTool, Async, Metrics, Remote Tools, Per-Tool Modules).
PR #72 is open against main. All tests pass across all modules.

## Recent Changes

### Issue #73 -- Enhanced Tool Model (builds on #60)

**Framework infrastructure in `agentensemble-core`:**
- Added `AbstractAgentTool`: base class with template method (`doExecute`), automatic
  timing/counting/logging, exception safety; `log()`, `metrics()`, `executor()` accessors
- Added `ToolContext`: immutable record carrying Logger, ToolMetrics, Executor -- injected
  by framework into `AbstractAgentTool` instances before first execution
- Added `ToolMetrics` interface + `NoOpToolMetrics` singleton default
- Added `ToolContextInjector`: friend-bridge so `ToolResolver` (different package) can
  inject `ToolContext` and manage the agentRole thread-local
- Enhanced `ToolResult` with optional `structuredOutput` field and typed `getStructuredOutput(Class<T>)` accessor
- Enhanced `ToolCallEvent` with `structuredResult` field; all metrics tagged by `(toolName, agentRole)`
- Updated `ToolResolver`: injects `ToolContext`, sets/clears agentRole thread-local, returns `ToolResult`
- Updated `AgentExecutor`: parallelizes multi-tool LLM turns via `CompletableFuture` + virtual threads;
  single-tool calls remain synchronous for efficiency
- Updated `ExecutionContext`: added `toolExecutor` and `toolMetrics` fields
- Updated `Ensemble.builder()`: exposes `toolExecutor(Executor)` and `toolMetrics(ToolMetrics)`

**Per-tool module restructure:**
- Added `buildSrc/` with `agentensemble.tool-conventions` precompiled convention plugin
  (java-library, vanniktech-publish, spotless, errorprone, jacoco, common test deps, coverage thresholds)
- All 7 existing tools migrated to sub-modules under `agentensemble-tools/<name>/`:
  `calculator`, `datetime`, `json-parser`, `file-read`, `file-write`, `web-search`, `web-scraper`
- Each tool converted to extend `AbstractAgentTool` (`doExecute` instead of `execute`)
- New sub-packages: `net.agentensemble.tools.{calculator,datetime,json,io,web.search,web.scraper}`
- Added BOM module at `agentensemble-tools/bom/`
- `settings.gradle.kts`: updated with all sub-modules + `pluginManagement` for plugin version resolution

**Remote tools:**
- Added `ProcessAgentTool` (`agentensemble-tools/process/`) with formal subprocess protocol spec:
  - Input: `{"input":"..."}` → stdin; output: `{"output":"...","success":true}` or structured variant
  - Non-zero exit → failure using stderr; configurable timeout + process kill on expiry
  - Gracefully handles processes that do not read stdin (BrokenPipe caught and logged)
- Added `HttpAgentTool` (`agentensemble-tools/http/`) wrapping REST endpoints:
  - GET: input as `?input=<encoded>` query param
  - POST: input as request body (auto `application/json` for JSON input, `text/plain` otherwise)
  - Custom headers, timeout, injectable HttpClient; GET/POST factory methods

**Micrometer integration:**
- Added `agentensemble-metrics-micrometer` module with `MicrometerToolMetrics`:
  - `agentensemble.tool.executions` counter tagged by `(tool_name, agent_role, outcome)`
  - `agentensemble.tool.duration` timer tagged by `(tool_name, agent_role)`
  - Custom metric support via `incrementCounter()` and `recordValue()`
  - All tag constants are public for external registry queries

**Documentation:**
- Updated `getting-started/installation.md` with per-tool deps, BOM, module table
- Updated `guides/tools.md` with AbstractAgentTool, ToolContext, parallel execution
- Updated `guides/built-in-tools.md` with per-module installation, updated package names
- Added `guides/remote-tools.md`: ProcessAgentTool, HttpAgentTool, protocol spec
- Added `guides/metrics.md`: ToolMetrics, MicrometerToolMetrics, Prometheus queries, custom metrics
- Updated `mkdocs.yml` navigation

**Examples:**
- Added `RemoteToolExample.java`: ProcessAgentTool (Python) + HttpAgentTool
- Added `MetricsExample.java`: MicrometerToolMetrics with SimpleMeterRegistry, prints metrics
- Updated `agentensemble-examples/build.gradle.kts` with all tool module deps

### Issue #60 -- agentensemble-tools module (v1.0.0) [superseded by #73 restructure]
Original flat `net.agentensemble.tools` package with 7 tools; replaced by per-module layout.

## Next Steps

- Review and merge PR #72 (feature/60-built-in-tool-library)
- Consider future work: MCP (Model Context Protocol) integration, GraalVM polyglot tools

## Active Decisions and Considerations

- **Async strategy**: Java 21 virtual threads handle I/O-bound tools; no `AsyncAgentTool`
  interface needed -- blocking is cheap and the JVM handles parallelism transparently
- **Tool executor**: Configurable via `Ensemble.builder().toolExecutor(executor)`; default is
  `Executors.newVirtualThreadPerTaskExecutor()`
- **Module layout**: Per-tool modules under `agentensemble-tools/` parent directory; users
  add exactly the tools they need; BOM for version alignment
- **Metrics**: Pluggable via `ToolMetrics` interface; no metrics overhead by default (NoOpToolMetrics)
