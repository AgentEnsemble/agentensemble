# EN-003: Capability handshake protocol

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-002
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 9, 20

## Description

When a WebSocket connection is established between two ensembles, the server sends a
`capability_hello` message listing its shared tasks and tools. This extends the existing
`hello` message.

## Acceptance Criteria

- [ ] `CapabilityHelloMessage` protocol message (extends or accompanies existing `HelloMessage`)
- [ ] Message includes list of shared tasks (name + description) and shared tools (name + description)
- [ ] Serialization/deserialization via `MessageSerializer`
- [ ] Server sends capabilities automatically on new connection
- [ ] Client parses and stores remote capabilities for later use
- [ ] Backward compatible: existing v2.x clients that do not understand capabilities ignore the new fields

## Tests

- [ ] Unit tests for protocol serialization round-trip
- [ ] Unit test: empty capabilities list serializes correctly
- [ ] Unit test: unknown fields in message are ignored (forward compatibility)
- [ ] Integration test: two ensembles connect, exchange capabilities
- [ ] Integration test: client can enumerate remote capabilities after connect
