# Active Context

## Current Work Focus

Issues #60 and #73 (Enhanced Tool Model: AbstractAgentTool, Per-Tool Modules, Remote Tools,
Metrics) were implemented on `feature/60-built-in-tool-library` and merged to main via PR #72
(squash commit `706305e`). The feature branch was deleted. The project is now on main at the
post-merge state, ready for the next release.

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

- Release-please will open a Release PR for v1.0.0 (the squash commit was a `feat:` Conventional
  Commit, which triggers a minor/major bump depending on release-please config)
- Issues #77-#81 (Structured Delegation API and related features) are the next planned features
- Issue #74 (Tool Pipeline/Chaining) remains open for future development
- Consider future work: MCP (Model Context Protocol) integration, GraalVM polyglot tools

## Planned Issues: Structured Delegation API (#77-#81)

Five GitHub issues were created to enhance the delegation infrastructure. They follow a dependency
order: #77 is foundational; #78 and #79 depend on #77; #80 is independent; #81 depends on all
of #77-#79.

### #77 -- Structured delegation contract: DelegationRequest and DelegationResponse
- `DelegationRequest` (immutable builder): `taskId`, `agentRole`, `taskDescription`, `scope`,
  `priority` (enum: NORMAL/HIGH/CRITICAL), `expectedOutputSchema`, `maxOutputRetries`, `metadata`
- `DelegationResponse` (record): `taskId`, `status` (enum: SUCCESS/FAILURE/VALIDATION_ERROR),
  `workerRole`, `rawOutput`, `parsedOutput`, `artifacts`, `errors`, `metadata`, `duration`
- LLM-facing `@Tool` method keeps 2-param signature (Option C hybrid); DelegationRequest
  constructed internally; DelegationResponse serialized as JSON (Jackson) returned to LLM
- When `expectedOutputSchema` set: parse + validate via StructuredOutputParser; retry-on-invalid
  bounded by `maxOutputRetries`; VALIDATION_ERROR on exhaustion
- Applies to both `AgentDelegationTool` (peer) and `HierarchicalWorkflowExecutor` (manager) paths

### #78 -- Delegation policy hooks: DelegationPolicy
- New package `net.agentensemble.delegation.policy`
- `DelegationPolicy` (@FunctionalInterface): `evaluate(DelegationRequest, DelegationPolicyContext)`
- `DelegationPolicyResult` (sealed): `allow()`, `reject(String reason)`, `modify(DelegationRequest)`
- `DelegationPolicyContext` (record): `delegatingAgentRole`, `currentDepth`, `maxDepth`, `availableWorkerRoles`
- Policies registered via `Ensemble.Builder.delegationPolicy(DelegationPolicy...)` and threaded
  via `DelegationContext` (propagated through `descend()`)
- Evaluation: REJECT short-circuits; MODIFY replaces working request; ALLOW passes through
- Policy REJECT produces FAILURE `DelegationResponse` without invoking the worker executor

### #79 -- Delegation lifecycle events and correlation IDs
- New event records in `net.agentensemble.callback`:
  - `DelegationStartedEvent`: `delegationId`, `managerRole`, `workerRole`, `taskDescription`, `scope`, `priority`, `timestamp`
  - `DelegationCompletedEvent`: `delegationId`, `managerRole`, `workerRole`, `status`, `duration`, `timestamp`
  - `DelegationFailedEvent`: `delegationId`, `managerRole`, `workerRole`, `failureReason`, `duration`, `timestamp`
- `EnsembleListener` gains 3 new default no-op methods: `onDelegationStarted/Completed/Failed`
- `Ensemble.Builder` gains 3 lambda convenience methods: `onDelegationStarted/Completed/Failed`
- `ExecutionContext` gains 3 fire methods with exception isolation
- `delegationId` (from `DelegationRequest.taskId()`) set as MDC key during worker execution

### #80 -- Manager prompt extension hook: ManagerPromptStrategy
- `ManagerPromptStrategy` (interface): `buildSystemPrompt(ManagerPromptContext)`, `buildUserPrompt(ManagerPromptContext)`
- `ManagerPromptContext` (record): `agents`, `currentTask`, `previousOutputs`, `workflowDescription`
- `DefaultManagerPromptStrategy`: public class containing existing `ManagerPromptBuilder` logic; exposes `DEFAULT` singleton
- `ManagerPromptBuilder` deprecated in favor of `DefaultManagerPromptStrategy`
- `Ensemble.Builder.managerPromptStrategy(ManagerPromptStrategy)` -- defaults to `DefaultManagerPromptStrategy.DEFAULT`
- `HierarchicalWorkflowExecutor` calls registered strategy; no direct use of `ManagerPromptBuilder` remains

### #81 -- Constrained hierarchical mode: HierarchicalConstraints
- `HierarchicalConstraints` (builder): `requiredWorkers`, `allowedWorkers`, `maxCallsPerWorker`, `globalMaxDelegations`, `requiredStages`
- `ConstraintViolationException` (extends AgentEnsembleException): carries list of unsatisfied constraints
- Enforcement via built-in `DelegationPolicy` (registered before user policies): allowlist REJECT, per-worker cap REJECT, global cap REJECT
- Post-execution validation: required workers + required stages checked; `ConstraintViolationException` if unsatisfied
- Constraint status injected into manager system prompt via `ManagerPromptStrategy`
- `Ensemble.Builder.hierarchicalConstraints(HierarchicalConstraints)` -- no-op for non-HIERARCHICAL workflows

## Post-PR Review Fixes (commits 5e81c70 and 1134b52)

After PR #72 was opened, CI and Copilot review identified additional issues:

1. **Javadoc failure**: `ExecutionContext.java` had `{@link}` references to Lombok-generated
   builder methods (`toolExecutor(Executor)`, `toolMetrics(ToolMetrics)`) that Javadoc cannot
   resolve. Fixed by replacing with `{@code}` examples.

2. **FileReadTool/FileWriteTool symlink escape**: The `normalize()+startsWith()` sandbox check
   blocks `../` traversal but not symlinks inside `baseDir` pointing outside. Fixed by calling
   `toRealPath()` after verifying the file/directory exists, then re-validating against
   `baseDir.toRealPath()`. Tests added for both tools.

3. **ProcessAgentTool pipe buffer deadlock**: Sequential drain (wait-then-read) could deadlock
   if a child process wrote more than the OS pipe buffer (~64 KB) before the streams were read.
   Fixed by draining stdout/stderr concurrently on virtual threads before calling `waitFor()`.

4. **BOM artifact coordinates**: The `agentensemble-tools/bom` module had no explicit Maven
   coordinates; without them it would publish as `net.agentensemble:bom:1.0.0`. Added
   `coordinates("net.agentensemble", "agentensemble-tools-bom", ...)` to the BOM build file.

5. **README dependency snippet**: The quickstart showed `agentensemble-tools:1.0.0` which no
   longer exists as a published artifact. Updated to show the BOM + individual tool modules.

6. **WebScraperTool SSRF**: Added a security note in `docs/guides/built-in-tools.md` explaining
   the SSRF risk for untrusted URL inputs and recommended mitigations.

7. **Issue #74 created**: Tool Pipeline/Chaining feature (Unix-pipe-style `search -> filter -> format`)
   tracked as a future issue with design sketch and key questions.

## Active Decisions and Considerations

- **Async strategy**: Java 21 virtual threads handle I/O-bound tools; no `AsyncAgentTool`
  interface needed -- blocking is cheap and the JVM handles parallelism transparently
- **Tool executor**: Configurable via `Ensemble.builder().toolExecutor(executor)`; default is
  `Executors.newVirtualThreadPerTaskExecutor()`
- **Module layout**: Per-tool modules under `agentensemble-tools/` parent directory; users
  add exactly the tools they need; BOM for version alignment
- **Metrics**: Pluggable via `ToolMetrics` interface; no metrics overhead by default (NoOpToolMetrics)
