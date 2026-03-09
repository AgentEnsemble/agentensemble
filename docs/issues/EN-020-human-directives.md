# EN-020: Human directives

**Phase**: 3 -- Human Participation and Observability (v3.0.0-rc)
**Dependencies**: EN-001, Phase 1 complete
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Section 8

## Description

Implement non-blocking human directives that inject guidance into an ensemble's context for future task executions.

## Acceptance Criteria

- [ ] directive wire protocol message type
- [ ] Dashboard UI: text input per ensemble for sending directives
- [ ] Ensemble-side: store active directives, inject as context in task execution
- [ ] Directive expiration (optional TTL)
- [ ] Control plane directives: SET_MODEL_TIER, APPLY_PROFILE

## Tests

- [ ] Unit tests for directive storage and context injection
- [ ] Unit test: directive with TTL expires correctly
- [ ] Unit test: directive injected into agent prompt context
- [ ] Integration test: send directive via dashboard, verify context injection
