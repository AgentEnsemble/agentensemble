# EN-025: Shared memory with configurable consistency

**Phase**: 4 -- Advanced (v3.0.0)
**Dependencies**: Phase 2 complete, EN-011
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 16

## Description

Extend MemoryStore to support cross-ensemble shared memory with configurable consistency models (EVENTUAL, LOCKED, OPTIMISTIC, EXTERNAL).

## Acceptance Criteria

- [ ] SharedMemory builder: store, consistency model, lock provider
- [ ] Consistency enum: EVENTUAL, LOCKED, OPTIMISTIC, EXTERNAL
- [ ] LockProvider SPI with Redis and in-memory implementations
- [ ] SharedMemory.builder().consistency(Consistency.LOCKED).lockProvider(...) API
- [ ] Network-level shared memory registration

## Tests

- [ ] Unit tests for each consistency model
- [ ] Unit test: EVENTUAL last-write-wins
- [ ] Unit test: LOCKED exclusive access
- [ ] Unit test: OPTIMISTIC compare-and-swap retry
- [ ] Integration test: two ensembles writing to LOCKED scope
