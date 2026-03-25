
# Active Context

## Current Work

PR #249 open: `feat/en-001-003-network-foundation`
Closes #216 (EN-001), #217 (EN-002), #218 (EN-003).

### What was implemented

**EN-001 (#216): Long-running ensemble mode**
- `EnsembleLifecycleState` enum: STARTING, READY, DRAINING, STOPPED
- `Ensemble.start(int port)` / `stop()` lifecycle methods (idempotent)
- `drainTimeout(Duration)` builder field (default 5 min)
- JVM shutdown hook on `start()`
- Dashboard required at build time via `webDashboard()` for long-running mode

**EN-002 (#217): shareTask()/shareTool() on Ensemble builder**
- `SharedCapability` record (name, description, type)
- `SharedCapabilityType` enum (TASK, TOOL)
- `Ensemble.builder().shareTask(name, task)` / `.shareTool(name, tool)`
- Validation: unique names (duplicate rejected by `EnsembleValidator`)
- Does not affect one-shot `run()`
- Default empty list when no capabilities configured

**EN-003 (#218): Capability handshake protocol**
- `SharedCapabilityInfo` wire-protocol record (name, description, type as String)
- `HelloMessage` extended with optional `sharedCapabilities` field
  - Original `(String ensembleId, Instant startedAt, JsonNode snapshotTrace)` preserved
  - Backward compat: null omitted from JSON via `@JsonInclude(NON_NULL)`
  - Forward compat: `@JsonIgnoreProperties(ignoreUnknown = true)`

### Documentation
- New guide: `docs/guides/long-running-ensembles.md`
- New example: `docs/examples/long-running-ensemble.md`
- Updated reference: `docs/reference/ensemble-configuration.md`
- Updated navigation: `mkdocs.yml`

## Previous Context

- TOON context format on `feat/toon-context-format` (PR #248, merged).

## Next Steps

Remaining Ensemble Network Phase 1 tickets (in dependency order):
- EN-003 (#218) ← Done (this PR)
- EN-004 (#219): NetworkTask implementation (WebSocket transport)
- EN-005 (#220): NetworkTool implementation (WebSocket transport)
- EN-006 (#221): WorkRequest envelope and wire protocol extensions
- EN-007 (#222): Incoming work request handler

**Implementation order for next session:**
EN-006 is pure data types (no runtime deps) -- implement first.
EN-004 and EN-005 (NetworkTask, NetworkTool) share EN-003 as a dep.
EN-007 requires EN-001, EN-002, EN-006.
</content>
<parameter name="task_progress">
- [x] Create feature branch
- [x] Implement EnsembleLifecycleState enum (#216)
- [x] Implement SharedCapability + SharedCapabilityType (#217)
- [x] Implement SharedCapabilityInfo wire type (#218)
- [x] Modify Ensemble.java: fields, lifecycle, builder methods
- [x] Re-add validateSharedCapabilities to EnsembleValidator
- [x] Fix HelloMessage to extend original record (not replace)
- [x] Write and fix all test files
- [x] Fix Javadoc reference errors
- [x] Full build passes (./gradlew build)
- [x] Update docs/reference/ensemble-configuration.md
- [x] Update mkdocs.yml navigation
- [x] Create docs/guides/long-running-ensembles.md
- [x] Create docs/examples/long-running-ensemble.md
- [x] Commit and push all changes
- [x] Update memory bank
