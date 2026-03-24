# EN-021: Role-based gated reviews (requiredRole)

**Phase**: 3 -- Human Participation and Observability (v3.0.0-rc)
**Dependencies**: EN-001, existing review system
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 8

## Description

Extend the existing review system with requiredRole so that certain review gates can only be approved by humans with a specific role.

## Acceptance Criteria

- [ ] Review.builder().requiredRole(String role) builder method
- [ ] requiredRole field in review_requested wire protocol message
- [ ] Dashboard: show pending reviews as not actionable if user lacks required role
- [ ] No-timeout mode: timeout(Duration.ZERO) means wait indefinitely
- [ ] Out-of-band notification SPI: ReviewNotifier interface
- [ ] ReviewNotifier.slack(webhookUrl) implementation

## Tests

- [ ] Unit tests for role gating and infinite timeout
- [ ] Unit test: unqualified user cannot approve gated review
- [ ] Unit test: qualified user can approve
- [ ] Unit test: Duration.ZERO means wait forever
- [ ] Integration test: gated review queued, human connects with role, approves
