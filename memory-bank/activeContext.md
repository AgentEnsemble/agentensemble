
# Active Context

## Current Work

**PR #308 review feedback addressed** -- fixing CI failure and all 11 Copilot review comments.

### PR #308 fixes (on branch `feat/300-ensemble-control-api-phase2`)

**Failing test fix (RunState race condition):**
- `WebDashboardRunRequestTest#runRequest_level1_withEnsemble_receivesAcceptedAckAndResult` was
  failing because handler tasks complete near-instantaneously, causing `state.getStatus()` to
  return `RUNNING` by the time the run_ack was serialized.
- Fix: added `private final Status initialStatus` (immutable) to `RunState` + `getInitialStatus()`.
- `WebDashboard.handleRunRequest()` now uses `state.getInitialStatus().name()` for the ack.

**RunRequestParser fixes (7 Copilot comments):**
- `additionalContext` ordering: pre-computed description deterministically (description override
  first, then additionalContext appended) before the switch loop, eliminating Map iteration
  order dependency.
- Tool removal by name: changed from reference equality (`t == resolved`) to
  `t instanceof AgentTool at && at.name().equals(toolName)`. Resolves failures when the task's
  tool was constructed outside the catalog.
- Tool add de-duplication: added name-based pre-check before adding to prevent duplicates.
- `expectedOutput` type validation: explicit `instanceof String` check with clear error message.
- Tool name type validation: explicit `instanceof String && !isBlank()` check.
- `context` field validation: validates it's a List before casting; validates each entry is String.
- `findTaskIndex` Locale: all `toLowerCase()` calls updated to `toLowerCase(Locale.ROOT)`.
- Added `import java.util.Locale`.

**Ensemble.withTasks() (1 Copilot comment):**
- Added empty list check (`IAE`) and null element check (per-index NPE) with useful messages.
- Updated Javadoc `@param`/`@throws` to document new contracts.

**WebDashboard + WebSocketServer (2 Copilot comments):**
- Both `handleRunRequest` (WS) and `resolveExecutionEnsemble` (REST) now reject requests where
  `tasks` is present-but-empty or `taskOverrides` is present-but-empty (IAE / REJECTED ack).

**Test improvements (2 Copilot comments):**
- `WebDashboardRunRequestTest`: replaced all `Thread.sleep(200)` with latch-based `helloLatch`
  waiting for the first server message (eliminates fixed-time flakiness on CI).
- Renamed L3 test to `runRequest_level3_withDynamicTasks_receivesAck` (matches actual assertions).

**Coverage (Codecov comment):**
- Added 9 `withTasks()` tests in `EnsembleTest` covering happy path, preserving settings, and
  the three new validation error cases.
- Added 14 new tests in `RunRequestParserTest` covering all the new validations and behavior
  fixes (expectedOutput type, tool name type, context type, additionalContext ordering, tool
  removal by name, dedup on add).

---

**Ensemble Control API Phase 2 (GH #300)** -- Level 2/3 parameterization and WebSocket run submission.

### What was implemented

Four areas of new functionality across `agentensemble-core` and `agentensemble-web`:

**Task naming (`agentensemble-core`):**
- Added optional `String name` field to `Task` (first field in declaration, before `description`).
- Non-blank validation when set; null default preserves all existing code paths.
- `toBuilder()` correctly carries `name` through.

**Level 2: Per-task overrides (`agentensemble-web`):**
- `RunRequestParser.buildFromTemplateWithOverrides()` applies runtime overrides to the template
  ensemble's task list using `Task.toBuilder()`. Original tasks are never mutated.
- Override key matching: exact `Task.name` first (case-insensitive), then description prefix
  (first 50 chars, case-insensitive).
- Override fields: `description`, `expectedOutput`, `model` (ModelCatalog), `maxIterations`,
  `additionalContext` (appended to description), `tools.add`/`tools.remove` (ToolCatalog).
- `RunConfiguration` record gained `List<Task> overrideTasks` (null for Level 1).

**Level 3: Dynamic task creation (`agentensemble-web`):**
- `RunRequestParser.buildFromDynamicTasks()` builds a full task list from JSON definitions.
- Context DAG resolution: `$name` and `$N` (0-based index) references.
- Circular dependency detection using Kahn's topological sort algorithm.
- `outputSchema` (JSON Schema) injected as structured output instructions into `expectedOutput`.

**WebSocket run submission (`agentensemble-web`):**
- New `RunRequestMessage` protocol record (Level 1/2/3 in one message).
- `ClientMessage` sealed interface updated (added `RunRequestMessage`).
- `WebDashboard.handleRunRequest()` dispatches to Level 1/2/3 parser, calls
  `RunManager.submitRun()`, sends `run_ack` immediately, sends `run_result` on completion
  targeted to originating session only.
- `Ensemble.withTasks(List<Task>)` -- new method that copies the template ensemble's key execution
  settings but replaces the task list. Used by `handleRunRequest` for Level 2/3 runs.
- `WebDashboard.parseRunOptions()` -- converts raw options map to `RunOptions`.

**Tests added:**
- `TaskTest` -- 8 new tests for `Task.name` field
- `RunRequestParserTest` -- 58 tests total (Level 1/2/3, all override fields, DAG, error cases)
- `RunRequestMessageTest` -- 10 serialization/deserialization tests
- `WebDashboardRunRequestTest` -- 4 WS integration tests (no-ensemble REJECTED, Level 1 ACCEPTED,
  Level 3 dispatch, server stability)

**Build:** All tests pass. Coverage meets thresholds. Spotless formatting clean.

---

## Previous Work

**`agentensemble-executor` module** -- direct in-process invocation from external workflow engines.

### What was implemented

New standalone module `agentensemble-executor` (package `net.agentensemble.executor`) with no
Temporal SDK dependency. Enables calling AgentEnsemble directly from any external workflow
orchestrator (Temporal, AWS Step Functions, Kafka Streams, etc.) with task-level or
ensemble-level granularity.

**DTOs (request/result, Lombok @Value @Builder):**
- `AgentSpec` -- agent role, goal, background, tool names, max iterations
- `TaskRequest` -- description, expectedOutput, agent, context, inputs, modelName
- `TaskResult` -- output, durationMs, toolCallCount, exitReason
- `EnsembleRequest` -- list of TaskRequests (@Singular), workflow mode, inputs, modelName
- `EnsembleResult` -- finalOutput, taskOutputs, totalDurationMs, totalToolCalls, exitReason
- `HeartbeatDetail` -- serializable heartbeat payload (eventType, description, taskIndex, iteration)

**Core executors:**
- `TaskExecutor` -- executes a single TaskRequest in-process; heartbeat via `Consumer<Object>`
- `EnsembleExecutor` -- executes a full EnsembleRequest in-process; same heartbeat API

**Heartbeat integration:**
- `HeartbeatEnsembleListener` -- EnsembleListener that forwards 6 event types to a Consumer

**Configuration (worker-side, never serialized):**
- `ModelProvider` / `ToolProvider` -- interfaces for model and tool resolution by name
- `SimpleModelProvider` / `SimpleToolProvider` -- map-backed implementations with builders

**Test doubles (in main jar, usable in users' test code):**
- `FakeTaskExecutor` -- extends `TaskExecutor`; rule-based `whenDescriptionContains()`, `whenAgentRole()`, `alwaysReturns()` factories; no LLM, no network
- `FakeEnsembleExecutor` -- same pattern, each task matched independently; last task output = `finalOutput()`

**Tests:** 98 tests passing (66 core + 18 `FakeTaskExecutorTest` + 14 `FakeEnsembleExecutorTest`; 0.85 line / 0.70 branch coverage)
**Documentation:** `docs/guides/executor-integration.md` (full Temporal integration + testing guide), `mkdocs.yml` nav
**Example:** `ExecutorExample.java` + `runExecutor` Gradle task

## Previous Context

Branch `feat/299-ensemble-control-api-phase1` -- Issue #299.
Ensemble Control API Phase 1: catalogs, RunManager, REST endpoints for external run submission.

### What was implemented

**#299 -- Ensemble Control API Phase 1:**
- `ToolCatalog` -- name-keyed `AgentTool` registry with builder and resolve/find/list/contains
- `ModelCatalog` -- alias-keyed `ChatModel` registry with streaming support and provider detection
- `RunState` -- mutable per-run lifecycle tracker with `Status` enum (ACCEPTED/RUNNING/COMPLETED/FAILED/CANCELLED/REJECTED)
- `RunManager` -- concurrency-limited async run executor using `Semaphore` + virtual threads + eviction
- `RunRequestParser` -- Level 1 template+inputs configuration builder
- `RunAckMessage` / `RunResultMessage` -- new `ServerMessage` sealed interface permits (run_ack, run_result)
- `WebDashboard.Builder` extended with `toolCatalog()`, `modelCatalog()`, `maxConcurrentRuns()`, `maxRetainedCompletedRuns()`
- REST endpoints added to `WebSocketServer`: `POST /api/runs`, `GET /api/runs`, `GET /api/runs/{runId}`, `GET /api/capabilities`
- `WebDashboard.stop()` shuts down `RunManager` executor
- `ensembleSupplier` pattern provides the template ensemble to route handlers
- Guide: `docs/guides/ensemble-control-api.md`
- Reference updated: `docs/reference/ensemble-configuration.md`
- Navigation updated: `mkdocs.yml`

## Previous Context (IO-003)

Branch `feat/io-003-iteration-snapshots` -- Issue #287 (IO-003).
Persist LLM iteration data in late-join snapshots for conversation replay.

### What was implemented

**EN-001 (#216): Long-running ensemble mode**
- `EnsembleLifecycleState` enum: STARTING, READY, DRAINING, STOPPED
- `Ensemble.start(int port)` / `stop()` lifecycle methods (idempotent, thread-safe)
- `drainTimeout(Duration)` builder field (default 5 min; drain logic pending future work)
- `stop()` gates on `ownsDashboardLifecycle` -- consistent with one-shot `run()` contract
- JVM shutdown hook registered on `start()`
- Dashboard required at build time via `webDashboard()` for long-running mode

**EN-002 (#217): shareTask()/shareTool() on Ensemble builder**
- `SharedCapability` record (name, description, type)
- `SharedCapabilityType` enum (TASK, TOOL)
- `Ensemble.builder().shareTask(name, task)` / `.shareTool(name, tool)`
- `shareTool()` uses `tool.description()` for capability metadata
- Validation: unique names detected by `EnsembleValidator`
- Does not affect one-shot `run()`

**EN-003 (#218): Capability handshake protocol**
- `SharedCapabilityInfo` wire-protocol record (name, description, type as String)
- `HelloMessage` extended with optional `sharedCapabilities` field
  - Original `(String ensembleId, Instant startedAt, JsonNode snapshotTrace)` preserved
  - `@JsonInclude(NON_NULL)` omits null fields from wire; null = one-shot ensemble
  - Backward compat: `MessageSerializer` uses `FAIL_ON_UNKNOWN_PROPERTIES=false`

### Documentation
- New guide: `docs/guides/long-running-ensembles.md`
- New example: `docs/examples/long-running-ensemble.md`
- Updated reference: `docs/reference/ensemble-configuration.md`
- Updated navigation: `mkdocs.yml`

## Previous Context

- TOON context format on `feat/toon-context-format` (PR #248, merged).

## Viz Observability Work (2026-03-30)

Analyzed gaps in tool and agent I/O visibility in the live dashboard.
Created design doc `docs/design/27-viz-observability.md` and 5 GitHub issues:

- **#285** IO-001: Enrich `ToolCallEvent` with `taskIndex` and `outcome` (Java-side)
- **#286** IO-002: Add `TaskInputEvent` for first-class agent input capture (Java-side)
- **#287** IO-003: Persist LLM iteration data in late-join snapshots (Java-side)
- **#288** IO-004: Viz tool call detail panel with formatted I/O (viz-side)
- **#289** IO-005: Viz agent conversation thread view (viz-side)

Key finding: the Java side already sends tool arguments/results and LLM message buffers
over WebSocket. The main Java gaps are: `ToolCallEvent` missing `taskIndex`/`outcome`
(hardcoded in listener), no first-class "agent input" event, and LLM iterations are
ephemeral (lost on late-join). The viz side needs UI components to surface this data.

## Next Steps

**Viz Observability (IO-001 through IO-005):**
- IO-001, IO-002, IO-003 are independent Java-side changes (can be parallelized)
- IO-004, IO-005 depend on the Java work

**Ensemble Network Phase 1 (remaining):**
- EN-004 (#219): NetworkTask implementation (WebSocket transport)
- EN-005 (#220): NetworkTool implementation (WebSocket transport)
- EN-006 (#221): WorkRequest envelope and wire protocol extensions
- EN-007 (#222): Incoming work request handler

**Implementation order for next session:**
EN-006 is pure data types (no runtime deps) -- implement first.
EN-004 and EN-005 (NetworkTask, NetworkTool) share EN-003 as a dep.
EN-007 requires EN-001, EN-002, EN-006.
