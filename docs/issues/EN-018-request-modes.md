# EN-018: Request modes (Await / Async / Deadline)

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-004, EN-005, EN-013
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Sections 6, 7

## Description

Implement the three caller-side request modes for NetworkTask and NetworkTool.

## Acceptance Criteria

- [ ] RequestMode.AWAIT -- block until result (existing behavior, formalized)
- [ ] RequestMode.ASYNC -- submit and return immediately; result delivered via callback
- [ ] RequestMode.AWAIT_WITH_DEADLINE -- block up to N, then continue
- [ ] .mode(RequestMode) on NetworkTask/NetworkTool builders
- [ ] .onComplete(Consumer) callback for ASYNC mode
- [ ] .deadline(Duration) and .onDeadline(DeadlineAction) for DEADLINE mode

## Tests

- [ ] Unit tests for each mode
- [ ] Unit test: ASYNC callback fires on result delivery
- [ ] Unit test: DEADLINE mode continues after timeout
- [ ] Integration test: async mode with callback delivery
