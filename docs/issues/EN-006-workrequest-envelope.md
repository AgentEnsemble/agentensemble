# EN-006: WorkRequest envelope and wire protocol extensions

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-004, EN-005
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 5, 20

## Description

Define and implement the `WorkRequest` record and all new wire protocol message types for
cross-ensemble communication.

## Acceptance Criteria

- [ ] `WorkRequest` record with fields: requestId, from, task, context, priority, deadline, delivery, traceContext, cachePolicy, cacheKey
- [ ] `Priority` enum: CRITICAL, HIGH, NORMAL, LOW
- [ ] `DeliverySpec` record: method, address
- [ ] `DeliveryMethod` enum: WEBSOCKET, QUEUE, TOPIC, WEBHOOK, STORE, BROADCAST_CLAIM, NONE
- [ ] `CachePolicy` enum: USE_CACHED, FORCE_FRESH
- [ ] Protocol messages: `task_request`, `task_accepted`, `task_progress`, `task_response`, `tool_request`, `tool_response`
- [ ] Jackson serialization/deserialization for all types
- [ ] W3C trace context field (`traceparent`, `tracestate`) on all cross-ensemble messages

## Tests

- [ ] Unit tests for all record types (construction, validation, accessors)
- [ ] Unit tests for all enum types
- [ ] Serialization round-trip tests for all protocol message types
- [ ] Unit test: unknown fields ignored during deserialization (forward compatibility)
