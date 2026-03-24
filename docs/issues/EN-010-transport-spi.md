# EN-010: Transport SPI and simple mode

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-006
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 6, 18

## Description

Define the transport SPI that abstracts the underlying messaging mechanism. Implement the simple mode (in-process, direct WebSocket) as the default.

## Acceptance Criteria

- [ ] Transport SPI interface: send(WorkRequest), receive(), deliver(WorkResponse)
- [ ] SimpleTransport implementation (in-process queues + WebSocket)
- [ ] Transport.websocket() factory
- [ ] SPI is pluggable: users can implement custom transports

## Tests

- [ ] Unit tests for SPI contract
- [ ] Unit test: SimpleTransport delivers messages in-process
- [ ] Unit test: factory methods
