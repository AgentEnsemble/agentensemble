# EN-019: Multi-ensemble dashboard (viz /network route)

**Phase**: 3 -- Human Participation and Observability (v3.0.0-rc)
**Dependencies**: EN-001, EN-003, Phase 1 complete
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 8

## Description

Add a /network route to agentensemble-viz that shows all ensembles in the network, their status, capabilities, and queue depth.

## Acceptance Criteria

- [ ] /network route in agentensemble-viz
- [ ] Network topology view (ensembles as nodes, connections as edges)
- [ ] Per-ensemble status: lifecycle state, queue depth, active tasks, capabilities
- [ ] Drill-down: click ensemble to see its internal task timeline/flow
- [ ] WebSocket connection to each ensemble (or aggregating portal)
- [ ] React components for network view

## Tests

- [ ] Unit tests for network state rendering
- [ ] Unit test: ensemble node shows correct status
- [ ] Unit test: drill-down renders internal view
- [ ] Integration test: live network with multiple ensembles
