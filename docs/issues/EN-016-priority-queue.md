# EN-016: Priority queue with aging

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-006
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 7

## Description

Implement the internal priority queue that orders incoming work by priority level with configurable aging for low-priority items.

## Acceptance Criteria

- [ ] PriorityWorkQueue with CRITICAL > HIGH > NORMAL > LOW ordering
- [ ] FIFO within same priority level
- [ ] Configurable age-based priority promotion (prevent starvation)
- [ ] task_accepted response with queue position and ETA estimate
- [ ] Queue depth metrics (Micrometer)

## Tests

- [ ] Unit tests for ordering, aging, ETA calculation
- [ ] Unit test: FIFO within same priority
- [ ] Unit test: aging promotes LOW to NORMAL after configured interval
- [ ] Unit test: metrics exposed correctly
