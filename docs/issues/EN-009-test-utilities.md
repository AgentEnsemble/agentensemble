# EN-009: Test utilities -- stubs and recording

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-004, EN-005
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 13

## Description

Provide test doubles for `NetworkTask` and `NetworkTool` so users can test ensembles in
isolation without real network connections.

## Acceptance Criteria

- [ ] `NetworkTask.stub(String ensemble, String task, String response)` -- returns canned response
- [ ] `NetworkTask.recording(String ensemble, String task)` -- records requests for assertion
- [ ] `NetworkTool.stub(String ensemble, String tool, String result)` -- returns canned result
- [ ] `NetworkTool.recording(String ensemble, String tool)` -- records calls for assertion
- [ ] `RecordingNetworkTask.lastRequest()` -- last request string
- [ ] `RecordingNetworkTask.requests()` -- all requests
- [ ] `RecordingNetworkTask.callCount()` -- number of invocations
- [ ] Same recording API for `RecordingNetworkTool`
- [ ] All stubs/recordings implement `AgentTool` (usable in task builder)

## Tests

- [ ] Unit tests for stub returning canned response
- [ ] Unit tests for recording capturing requests
- [ ] Unit test: callCount increments correctly
- [ ] Unit test: stubs work in a real ensemble `run()` call
