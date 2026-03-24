# EN-013: Pluggable delivery methods

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-010, EN-011
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 5, 18

## Description

Implement the delivery method abstraction so work responses can be delivered via the caller-specified method.

## Acceptance Criteria

- [ ] DeliveryHandler SPI: deliver(DeliverySpec, WorkResponse)
- [ ] Implementations: WebSocket, Queue, Topic, Webhook (HTTP POST), Store, BroadcastClaim
- [ ] DeliveryMethod.NONE handler (no-op)
- [ ] Registration mechanism for custom delivery handlers

## Tests

- [ ] Unit tests for each delivery method
- [ ] Integration test: request via WebSocket, result via Kafka topic
- [ ] Unit test: custom delivery handler registration
