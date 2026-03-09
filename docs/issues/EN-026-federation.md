# EN-026: Federation (cross-namespace capability sharing)

**Phase**: 4 -- Advanced (v3.0.0)
**Dependencies**: EN-003, Phase 2 complete
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Section 12

## Description

Implement cross-realm discovery and capability sharing for ensembles in different K8s namespaces or clusters.

## Acceptance Criteria

- [ ] Realm concept: namespace as discovery boundary
- [ ] capacity_update protocol message with realm and shareable fields
- [ ] Cross-realm capability query and routing
- [ ] Routing hierarchy: local -> realm -> federation
- [ ] Load-based routing (prefer least-loaded provider)

## Tests

- [ ] Unit tests for cross-realm discovery
- [ ] Unit test: routing hierarchy (local preferred over federation)
- [ ] Unit test: capacity advertisement with shareable flag
- [ ] Integration test: ensemble in realm A uses capability from realm B
