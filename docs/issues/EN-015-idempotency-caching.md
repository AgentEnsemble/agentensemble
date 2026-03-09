# EN-015: Idempotency and result caching

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-010, EN-011
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Sections 15, 18

## Description

Implement idempotency (duplicate request detection) and optional result caching with pluggable shared store.

## Acceptance Criteria

- [ ] Idempotency check on requestId before task execution
- [ ] TTL-based idempotency cache (in-memory for simple mode, Redis for durable mode)
- [ ] ResultCache SPI with inMemory() and redis() factories
- [ ] cacheKey + maxAge support on WorkRequest
- [ ] CachePolicy.USE_CACHED / FORCE_FRESH handling

## Tests

- [ ] Unit tests for deduplication and cache hit/miss
- [ ] Integration test: retry same requestId, verify single execution
- [ ] Unit test: cache TTL expiry
- [ ] Unit test: FORCE_FRESH bypasses cache
