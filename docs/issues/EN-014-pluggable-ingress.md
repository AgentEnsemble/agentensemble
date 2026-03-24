# EN-014: Pluggable ingress methods

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-010, EN-011
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 5, 18

## Description

Implement multiple ingress sources so work can arrive at an ensemble via WebSocket, queue, HTTP API, or topic subscription.

## Acceptance Criteria

- [ ] Ingress SPI: normalized WorkRequest production
- [ ] WebSocketIngress (existing, adapted)
- [ ] QueueIngress (pull from durable queue)
- [ ] HttpIngress (POST /api/work endpoint)
- [ ] TopicIngress (subscribe to Kafka/Redis topic)
- [ ] Multiple ingress sources active simultaneously

## Tests

- [ ] Unit tests for each ingress type
- [ ] Integration test: submit work via HTTP, result via queue
- [ ] Unit test: multiple ingress sources active concurrently
