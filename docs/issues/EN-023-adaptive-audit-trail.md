# EN-023: Leveled audit trail with dynamic rules

**Phase**: 3 -- Human Participation and Observability (v3.0.0-rc)
**Dependencies**: Phase 1 complete
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 14

## Description

Implement the audit trail system with configurable levels (OFF/MINIMAL/STANDARD/FULL) and dynamic rule-based escalation.

## Acceptance Criteria

- [ ] AuditLevel enum: OFF, MINIMAL, STANDARD, FULL
- [ ] AuditPolicy builder with rules (metric, event, schedule, human-triggered)
- [ ] AuditRule with condition, target level, target ensemble(s), optional duration
- [ ] AuditSink SPI with log(), database(), eventStream() factories
- [ ] Per-ensemble and network-level audit level configuration
- [ ] Temporary escalation with automatic revert
- [ ] Audit records: immutable, timestamped, trace-ID-correlated

## Tests

- [ ] Unit tests for rule evaluation, escalation, revert
- [ ] Unit test: metric-driven escalation
- [ ] Unit test: event-driven escalation
- [ ] Unit test: temporary escalation reverts after duration
- [ ] Unit test: human-triggered escalation
- [ ] Integration test: task failure triggers FULL escalation for 10 minutes
