# EN-002: `shareTask()` and `shareTool()` on Ensemble builder

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-001
**Design Doc**: [24-ensemble-network.md](../design/24-ensemble-network.md) -- Section 4

## Description

Add builder methods for declaring tasks and tools that this ensemble shares with the
network. Shared capabilities are stored and published during capability handshake.

## Acceptance Criteria

- [ ] `Ensemble.builder().shareTask(String name, Task task)` builder method
- [ ] `Ensemble.builder().shareTool(String name, AgentTool tool)` builder method
- [ ] `SharedCapability` record: name, description, type (TASK or TOOL)
- [ ] `Ensemble.getSharedCapabilities()` accessor returns immutable list
- [ ] Validation: shared task/tool names must be unique within an ensemble
- [ ] Validation: name must not be null or blank
- [ ] Validation: task/tool must not be null

## Tests

- [ ] Unit tests for builder methods
- [ ] Unit test: duplicate name throws ValidationException
- [ ] Unit test: null/blank name throws ValidationException
- [ ] Unit test: null task/tool throws ValidationException
- [ ] Unit test: `getSharedCapabilities()` returns correct list
- [ ] Unit test: shared capabilities do not interfere with one-shot `run()`
