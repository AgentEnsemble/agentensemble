# EN-011: Redis transport implementation

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-010
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Sections 6, 18

## Description

Implement a Redis-backed transport using Redis Streams for durable request queues and Redis for result storage.

## Acceptance Criteria

- [ ] agentensemble-transport-redis Gradle module
- [ ] RedisRequestQueue implementing request queue SPI
- [ ] RedisResultStore implementing result store SPI
- [ ] Transport.durable(RequestQueue, ResultStore) factory
- [ ] Consumer group support (multiple replicas reading from same stream)
- [ ] TTL-based cleanup for processed messages

## Tests

- [ ] Integration tests with embedded Redis (Testcontainers)
- [ ] Unit test: consumer group load balancing
- [ ] Unit test: TTL cleanup
- [ ] Unit test: visibility timeout and redelivery
