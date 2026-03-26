
# Active Context

## Current Work

PR #249 open: `feat/en-001-003-network-foundation`
Closes #216 (EN-001), #217 (EN-002), #218 (EN-003).

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

## Next Steps

Remaining Ensemble Network Phase 1 tickets (in dependency order):
- EN-004 (#219): NetworkTask implementation (WebSocket transport)
- EN-005 (#220): NetworkTool implementation (WebSocket transport)
- EN-006 (#221): WorkRequest envelope and wire protocol extensions
- EN-007 (#222): Incoming work request handler

**Implementation order for next session:**
EN-006 is pure data types (no runtime deps) -- implement first.
EN-004 and EN-005 (NetworkTask, NetworkTool) share EN-003 as a dep.
EN-007 requires EN-001, EN-002, EN-006.
