# EN-024: Control plane directives (model tier switching)

**Phase**: 3 -- Human Participation and Observability (v3.0.0-rc)
**Dependencies**: EN-020
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Sections 8, 10

## Description

Implement control plane directives that modify ensemble behavior at runtime, starting with LLM model tier switching.

## Acceptance Criteria

- [ ] Ensemble.builder().fallbackModel(ChatModel) builder method
- [ ] SET_MODEL_TIER directive handler: switches between primary and fallback models
- [ ] Directive applies to new tasks only (in-flight tasks continue with current model)
- [ ] APPLY_PROFILE directive handler: applies operational profile to the network
- [ ] Rule-based automatic directives (e.g., cost threshold triggers fallback)

## Tests

- [ ] Unit tests for model switching
- [ ] Unit test: in-flight tasks not affected by model switch
- [ ] Unit test: rule-based directive fires on threshold
- [ ] Integration test: send directive, verify subsequent tasks use fallback model
