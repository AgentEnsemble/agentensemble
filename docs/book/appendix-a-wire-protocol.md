# Appendix A: Wire Protocol Reference

All messages are UTF-8 JSON with a `type` discriminator field. This appendix catalogs every
message type in the Ensemble Network wire protocol, extending the v2.1.0 protocol defined
in design document 16.

## Existing v2.1.0 Messages (Unchanged)

These messages are retained from the live dashboard protocol:

| Type | Direction | Purpose |
|---|---|---|
| `hello` | Server -> Client | Late-join state snapshot |
| `ensemble_started` | Server -> Client | Ensemble run begins |
| `ensemble_completed` | Server -> Client | Ensemble run ends |
| `task_started` | Server -> Client | Task execution begins |
| `task_completed` | Server -> Client | Task execution succeeds |
| `task_failed` | Server -> Client | Task execution fails |
| `tool_called` | Server -> Client | Tool invocation in ReAct loop |
| `delegation_started` | Server -> Client | Agent delegation begins |
| `delegation_completed` | Server -> Client | Agent delegation succeeds |
| `delegation_failed` | Server -> Client | Agent delegation fails |
| `review_requested` | Server -> Client | Review gate awaiting decision |
| `review_timed_out` | Server -> Client | Review gate timeout expired |
| `review_decision` | Client -> Server | Human review decision |
| `token` | Server -> Client | Streaming LLM token |
| `heartbeat` | Server -> Client | Connection keepalive |
| `ping` | Client -> Server | Client keepalive |
| `pong` | Server -> Client | Ping response |

## New v3.0.0 Messages: Registration

### `ensemble_register`

Sent by an ensemble when it joins the network, publishing its shared capabilities.

```json
{
  "type": "ensemble_register",
  "name": "kitchen",
  "realm": "hotel-downtown",
  "capabilities": {
    "sharedTasks": [
      {
        "name": "prepare-meal",
        "description": "Prepare a meal as specified",
        "tags": ["food", "kitchen"]
      }
    ],
    "sharedTools": [
      {
        "name": "check-inventory",
        "description": "Check ingredient availability",
        "tags": ["food", "inventory"]
      }
    ]
  }
}
```

### `ensemble_deregister`

Sent when an ensemble is shutting down.

```json
{
  "type": "ensemble_deregister",
  "name": "kitchen",
  "realm": "hotel-downtown"
}
```

### `capacity_update`

Periodic capacity advertisement (default: every 30 seconds).

```json
{
  "type": "capacity_update",
  "ensemble": "kitchen",
  "realm": "hotel-downtown",
  "status": "available",
  "currentLoad": 0.65,
  "maxConcurrent": 10,
  "availableCapacity": 3,
  "queueDepth": 7,
  "shareable": true
}
```

## New v3.0.0 Messages: Work Requests

### `task_request`

WorkRequest envelope for cross-ensemble task delegation.

```json
{
  "type": "task_request",
  "requestId": "maint-7721-valve-order",
  "from": "maintenance",
  "task": "purchase-parts",
  "context": "Order replacement valve model X-420 for building 2 boiler, urgent priority",
  "priority": "HIGH",
  "deadline": "PT30M",
  "delivery": {
    "method": "QUEUE",
    "address": "maintenance.results"
  },
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    "tracestate": "agentensemble=maintenance"
  },
  "cachePolicy": "FORCE_FRESH",
  "cacheKey": null
}
```

### `task_accepted`

Acknowledgment with queue position and estimated completion time.

```json
{
  "type": "task_accepted",
  "requestId": "maint-7721-valve-order",
  "queuePosition": 3,
  "estimatedCompletion": "PT15M"
}
```

### `task_progress`

Optional streaming progress update during task execution.

```json
{
  "type": "task_progress",
  "requestId": "maint-7721-valve-order",
  "status": "IN_PROGRESS",
  "message": "Searching vendors for model X-420...",
  "percentComplete": 30
}
```

### `task_response`

Work result (completed, failed, or rejected).

```json
{
  "type": "task_response",
  "requestId": "maint-7721-valve-order",
  "status": "COMPLETED",
  "result": "Ordered from SupplyCo, PO #4821, estimated delivery Thursday.",
  "durationMs": 45000,
  "tokenCount": 2841
}
```

Status values: `COMPLETED`, `FAILED`, `REJECTED`.

Failed response:

```json
{
  "type": "task_response",
  "requestId": "maint-7722",
  "status": "FAILED",
  "error": "No vendors found for model X-420 in the approved supplier list."
}
```

Rejected response (at hard capacity limit):

```json
{
  "type": "task_response",
  "requestId": "maint-7723",
  "status": "REJECTED",
  "reason": "AT_CAPACITY",
  "currentQueue": 200,
  "suggestion": "Try kitchen in realm hotel-airport"
}
```

### `tool_request`

Request to execute a shared tool on a remote ensemble.

```json
{
  "type": "tool_request",
  "requestId": "rs-8801",
  "from": "room-service",
  "tool": "check-inventory",
  "input": "wagyu beef",
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-11a2b3c4d5e6f7a8-01"
  }
}
```

### `tool_response`

Result of a shared tool execution.

```json
{
  "type": "tool_response",
  "requestId": "rs-8801",
  "status": "COMPLETED",
  "result": "Yes, 3 portions available",
  "durationMs": 120
}
```

## New v3.0.0 Messages: Human Interaction

### `directive`

Non-blocking guidance from a human or automated policy.

```json
{
  "type": "directive",
  "to": "room-service",
  "from": "manager:human",
  "content": "Guest in 801 is VIP, prioritize all their requests",
  "ttl": "P3D"
}
```

Control plane directive:

```json
{
  "type": "directive",
  "to": "kitchen",
  "from": "cost-policy:automated",
  "action": "SET_MODEL_TIER",
  "value": "FALLBACK"
}
```

### `query`

Request information from an ensemble.

```json
{
  "type": "query",
  "queryId": "q-4401",
  "to": "front-desk",
  "from": "manager:human",
  "question": "How many rooms are available tonight?"
}
```

### `query_response`

Response to a query.

```json
{
  "type": "query_response",
  "queryId": "q-4401",
  "answer": "47 rooms available: 12 standard, 28 deluxe, 7 suites."
}
```

### `notification`

Alert from an ensemble to a human.

```json
{
  "type": "notification",
  "from": "maintenance",
  "severity": "HIGH",
  "message": "Water leak detected in room 305, emergency response dispatched.",
  "targetRole": "manager"
}
```

## New v3.0.0 Messages: Network Coordination

### `profile_applied`

Broadcast when an operational profile is applied to the network.

```json
{
  "type": "profile_applied",
  "profile": "sporting-event-weekend",
  "appliedBy": "manager:human",
  "appliedAt": "2026-03-06T16:00:00Z"
}
```

### `capability_query`

Discovery query: who provides this capability?

```json
{
  "type": "capability_query",
  "queryId": "cq-001",
  "from": "room-service",
  "capability": "check-inventory",
  "tags": ["food"]
}
```

### `capability_response`

Discovery response: list of providers.

```json
{
  "type": "capability_response",
  "queryId": "cq-001",
  "providers": [
    {
      "ensemble": "kitchen",
      "realm": "hotel-downtown",
      "type": "TOOL",
      "healthy": true,
      "currentLoad": 0.65
    },
    {
      "ensemble": "kitchen",
      "realm": "hotel-airport",
      "type": "TOOL",
      "healthy": true,
      "currentLoad": 0.20
    }
  ]
}
```
