# EN-007: Incoming work request handler

**Phase**: 1 -- Foundation (v3.0.0-alpha)
**Dependencies**: EN-001, EN-002, EN-006
**Design Doc**: [18-ensemble-network.md](../design/18-ensemble-network.md) -- Sections 3, 4

## Description

When a long-running ensemble receives a `task_request` or `tool_request`, it executes the
corresponding shared task/tool and sends back the response.

## Acceptance Criteria

- [ ] Request dispatcher routes incoming `task_request` to the matching shared task
- [ ] Request dispatcher routes incoming `tool_request` to the matching shared tool
- [ ] Task execution: synthesize agent, run task pipeline, return output as `task_response`
- [ ] Tool execution: invoke tool directly, return result as `tool_response`
- [ ] Error handling: return error response for unknown task/tool name
- [ ] Error handling: return error response for task execution failure
- [ ] `task_accepted` acknowledgment sent immediately on receipt

## Tests

- [ ] Unit tests for dispatch logic (task and tool routing)
- [ ] Unit test: unknown task name returns error response
- [ ] Unit test: unknown tool name returns error response
- [ ] Unit test: task execution failure returns error response
- [ ] Integration test: full round-trip (request -> execute -> response)
- [ ] Integration test: task with tools executes correctly via network
