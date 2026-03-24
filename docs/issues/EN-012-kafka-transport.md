# EN-012: Kafka transport implementation

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-010
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 6, 18

## Description

Implement a Kafka-backed transport for durable topic-based delivery.

## Acceptance Criteria

- [ ] agentensemble-transport-kafka Gradle module
- [ ] KafkaRequestQueue implementing request queue SPI
- [ ] KafkaTopicDelivery for topic-based result delivery
- [ ] Consumer group support

## Tests

- [ ] Integration tests with embedded Kafka (Testcontainers)
- [ ] Unit test: consumer group
- [ ] Unit test: topic delivery
