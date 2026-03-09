# EN-001: Long-running ensemble mode (`Ensemble.start()`)

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: None (foundational)
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Sections 3, 17

## Description

Add a long-running execution mode to `Ensemble` where the ensemble starts, registers on a
port, and listens for incoming work indefinitely. The existing `Ensemble.run()` (one-shot)
is unchanged.

## Acceptance Criteria

- [ ] `Ensemble.start(int port)` method starts the WebSocket server and enters READY state
- [ ] `Ensemble.stop()` triggers graceful shutdown
- [ ] `EnsembleLifecycleState` enum: STARTING, READY, DRAINING, STOPPED
- [ ] Readiness/liveness lifecycle (reuse existing `WebSocketServer` infrastructure)
- [ ] State transitions: STARTING -> READY on server bind; READY -> DRAINING on `stop()` or SIGTERM; DRAINING -> STOPPED after drain timeout
- [ ] `drainTimeout(Duration)` on builder (default: 5 minutes)
- [ ] Existing `Ensemble.run()` one-shot mode is completely unchanged
- [ ] JVM shutdown hook registered for graceful shutdown

## Tests

- [ ] Unit tests for lifecycle state transitions (all 4 states)
- [ ] Unit test: `start()` on already-started ensemble is idempotent
- [ ] Unit test: `stop()` on already-stopped ensemble is idempotent
- [ ] Unit test: drain timeout respected
- [ ] Integration test: start, accept WebSocket connection, stop
- [ ] Integration test: SIGTERM triggers DRAINING -> STOPPED

## Documentation

- [ ] Javadoc for `start()`, `stop()`, `EnsembleLifecycleState`
- [ ] Update `docs/reference/ensemble-configuration.md` with `drainTimeout` field
