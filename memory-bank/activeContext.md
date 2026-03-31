
# Active Context

## Current Work

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
