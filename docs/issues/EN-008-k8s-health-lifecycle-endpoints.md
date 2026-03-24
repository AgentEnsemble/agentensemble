# EN-008: K8s health and lifecycle endpoints

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-001
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 17

## Description

Add HTTP endpoints for K8s liveness, readiness, and drain lifecycle management to the
existing WebSocket server.

## Acceptance Criteria

- [ ] `GET /api/health/live` -- returns 200 when process is alive
- [ ] `GET /api/health/ready` -- returns 200 only in READY state; 503 in STARTING, DRAINING, STOPPED
- [ ] `POST /api/lifecycle/drain` -- triggers transition to DRAINING state
- [ ] `GET /api/status` extended with `lifecycleState` field in response JSON
- [ ] All endpoints return JSON responses

## Tests

- [ ] Unit test: liveness returns 200 in all lifecycle states
- [ ] Unit test: readiness returns 200 in READY, 503 in STARTING/DRAINING/STOPPED
- [ ] Unit test: drain endpoint triggers DRAINING state
- [ ] Unit test: drain on already-draining ensemble is idempotent
- [ ] Unit test: status endpoint includes lifecycle state

## Documentation

- [ ] K8s deployment manifest example in docs
- [ ] Document `terminationGracePeriodSeconds` alignment with `drainTimeout`
