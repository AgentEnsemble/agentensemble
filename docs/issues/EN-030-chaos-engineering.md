# EN-030: Chaos engineering (ChaosExperiment)

**Phase**: 4 -- Advanced (v3.0.0)
**Dependencies**: Phase 2 complete
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 13

## Description

Implement built-in chaos engineering for fault injection into running networks with assertions on expected behavior.

## Acceptance Criteria

- [ ] agentensemble-chaos Gradle module (test scope)
- [ ] ChaosExperiment builder: name, target network, fault schedule, assertions
- [ ] Fault types: kill(ensemble), restore(ensemble), latency(ensemble, duration), dropMessages(ensemble, rate), degradeCapacity(ensemble, factor)
- [ ] Assertion types: circuitBreakerOpens, fallbackActivated, noDataLoss, allPendingRequestsResolve
- [ ] ChaosReport: passed, failures, timeline
- [ ] Fault injection at the transport layer (framework-controlled, not infrastructure)

## Tests

- [ ] Unit tests for fault injection and assertion evaluation
- [ ] Unit test: kill/restore lifecycle
- [ ] Unit test: latency injection measurable
- [ ] Unit test: assertion pass/fail detection
- [ ] Integration test: inject kitchen failure, assert circuit breaker behavior
