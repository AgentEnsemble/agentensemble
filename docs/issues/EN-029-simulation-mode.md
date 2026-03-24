# EN-029: Simulation mode

**Phase**: 4 -- Advanced (v3.0.0)
**Dependencies**: Phase 2 complete
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 13

## Description

Implement simulation tooling for modeling network behavior with simulated LLMs, time compression, and scenario definitions.

## Acceptance Criteria

- [ ] Simulation builder: network, scenario, chatModel, timeCompression
- [ ] Scenario builder: load profiles, failure profiles, latency profiles per ensemble
- [ ] SimulationChatModel.fast() -- deterministic, fast, configurable response characteristics
- [ ] LoadProfile: steady, ramp, spike
- [ ] FailureProfile: downAt(time, duration)
- [ ] LatencyProfile: fixed, multiply
- [ ] SimulationResult: bottlenecks, failure cascades, capacity report, token estimate
- [ ] Time compression (1 hour = 1 minute)

## Tests

- [ ] Unit tests for scenario construction
- [ ] Unit test: LoadProfile ramp generates correct request rate
- [ ] Unit test: FailureProfile kills and restores at correct times
- [ ] Unit test: time compression factor applied correctly
- [ ] Integration test: simulate peak load, verify capacity report
