# EN-005: `NetworkTool` implementation (WebSocket transport)

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-003
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 3, 4

## Description

Implement `NetworkTool` -- an `AgentTool` that executes a shared tool on a remote ensemble
over WebSocket. Lighter weight than `NetworkTask` (single tool call, not full task pipeline).

## Acceptance Criteria

- [ ] `NetworkTool` class implementing `AgentTool`
- [ ] `NetworkTool.from(String ensemble, String toolName)` factory
- [ ] Sends `tool_request` message over WebSocket
- [ ] Blocks on `CompletableFuture` until `tool_response` arrives
- [ ] Timeout support
- [ ] `tool_request` and `tool_response` protocol messages
- [ ] Agent sees NetworkTool as a regular tool (transparent)

## Tests

- [ ] Unit tests with mock WebSocket
- [ ] Unit test: successful round-trip
- [ ] Unit test: timeout handling
- [ ] Unit test: error response
- [ ] Integration test: agent in ensemble A calls NetworkTool on ensemble B
