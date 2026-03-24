# EN-004: `NetworkTask` implementation (WebSocket transport)

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-003
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 3, 4

## Description

Implement `NetworkTask` -- an `AgentTool` that delegates a full task execution to a remote
ensemble over WebSocket. This is the core cross-ensemble delegation mechanism.

## Acceptance Criteria

- [ ] `NetworkTask` class implementing `AgentTool`
- [ ] `NetworkTask.from(String ensemble, String taskName)` factory
- [ ] Sends `task_request` message over WebSocket
- [ ] Blocks on `CompletableFuture` until `task_response` arrives (AWAIT mode)
- [ ] Connect timeout support (default: 10 seconds)
- [ ] Execution timeout support (default: 30 minutes)
- [ ] `task_request` and `task_response` protocol messages
- [ ] Agent sees NetworkTask as a regular tool (transparent)
- [ ] Tool name and description derived from the shared task definition

## Tests

- [ ] Unit tests with mock WebSocket
- [ ] Unit test: timeout fires when no response
- [ ] Unit test: connect timeout fires when ensemble unreachable
- [ ] Unit test: successful round-trip returns correct result
- [ ] Unit test: error response propagates as tool failure
- [ ] Integration test: ensemble A calls NetworkTask on ensemble B, gets result
- [ ] Integration test: agent in ensemble A uses NetworkTask in ReAct loop
