# EN-017: Scheduled / proactive tasks

**Phase**: 2 -- Durable Transport (v3.0.0-beta)
**Dependencies**: EN-010, EN-013
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Sections 3, 6

## Description

Implement scheduled tasks that run on a cron/interval and broadcast their results to a topic.

## Acceptance Criteria

- [ ] ScheduledTask builder: name, task, schedule (cron or interval), broadcastTo topic
- [ ] Ensemble.builder().scheduledTask(ScheduledTask) builder method
- [ ] Scheduler integration (ScheduledExecutorService or similar)
- [ ] Broadcast result to specified topic via delivery handler
- [ ] Scheduled tasks stop during DRAINING state

## Tests

- [ ] Unit tests for scheduling and broadcast
- [ ] Unit test: interval-based scheduling fires at correct times
- [ ] Unit test: scheduled tasks stop during DRAINING
- [ ] Integration test: scheduled task fires, result appears on topic
