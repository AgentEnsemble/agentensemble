# EN-027: Operational profiles

**Phase**: 4 -- Advanced (v3.0.0)
**Dependencies**: Phase 2 complete, EN-024
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Section 7

## Description

Implement operational profiles that allow pre-planned capacity adjustments for anticipated load changes.

## Acceptance Criteria

- [ ] NetworkProfile builder: name, per-ensemble capacity settings, pre-load directives
- [ ] Capacity configuration: replicas, maxConcurrent, dormant flag
- [ ] network.applyProfile(profile) method
- [ ] Scheduled profile switching (cron-based)
- [ ] Profile broadcasts profile_applied message to all ensembles

## Tests

- [ ] Unit tests for profile construction and application
- [ ] Unit test: dormant flag stops ensemble
- [ ] Unit test: pre-load injects context into shared memory
- [ ] Integration test: apply profile, verify capacity changes
