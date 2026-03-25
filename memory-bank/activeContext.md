# Active Context

## Current Work

Branch: `feat/en-001-003-network-foundation` (PR created).

Implemented EN-001, EN-002, EN-003 -- the first three tickets of the Ensemble Network Phase 1:

- **EN-001 (#216)**: Long-running ensemble mode (`Ensemble.start(int port)` / `stop()`)
  - `EnsembleLifecycleState` enum (STARTING, READY, DRAINING, STOPPED)
  - `drainTimeout(Duration)` builder field (default 5 min)
  - Idempotent lifecycle, JVM shutdown hook
  - Auto-creates dashboard when none pre-configured

- **EN-002 (#217)**: `shareTask()` / `shareTool()` on Ensemble builder
  - `SharedCapability` record + `SharedCapabilityType` enum
  - Validation: unique names, not null/blank
  - Does not affect one-shot `run()`

- **EN-003 (#218)**: Capability handshake protocol
  - `SharedCapabilityInfo` wire-protocol record
  - `HelloMessage` extended with optional `sharedCapabilities` field
  - Backward + forward compatible

Documentation: new guide, example page, reference update, mkdocs nav update.

## Previous Context

- TOON context format work on `feat/toon-context-format` (PR #207/#248, merged).
- P3 performance fixes on `fix/error-prone-pmd-p3` (GH #205).

## Next Steps

Remaining Ensemble Network Phase 1 tickets:
- EN-004 (#219): NetworkTask implementation (WebSocket transport)
- EN-005 (#220): NetworkTool implementation (WebSocket transport)
- EN-006 (#221): WorkRequest envelope and wire protocol extensions
- EN-007 (#222): Incoming work request handler