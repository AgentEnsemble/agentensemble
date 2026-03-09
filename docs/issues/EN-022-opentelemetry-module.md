# EN-022: OpenTelemetry integration module

**Phase**: 3 -- Human Participation and Observability (v3.0.0-rc)
**Dependencies**: EN-006
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Section 10

## Description

Create agentensemble-telemetry-opentelemetry module that creates OTel spans at framework boundaries.

## Acceptance Criteria

- [ ] agentensemble-telemetry-opentelemetry Gradle module
- [ ] OTel dependency: io.opentelemetry:opentelemetry-api
- [ ] Span creation: ensemble.run, task.execute, llm.call, tool.execute, network.delegate, network.handle
- [ ] W3C trace context extraction from WorkRequest and injection into outgoing requests
- [ ] AgentEnsemble-specific span attributes
- [ ] ExecutionTrace.traceId field for linking to external trace viewer

## Tests

- [ ] Unit tests with in-memory span exporter
- [ ] Unit test: span hierarchy is correct (ensemble > task > tool)
- [ ] Unit test: trace context propagates across ensembles
- [ ] Integration test: cross-ensemble delegation produces correlated spans
