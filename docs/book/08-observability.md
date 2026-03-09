# Chapter 8: Observability

## You Cannot Debug What You Cannot See

When a single ensemble runs in a single JVM, debugging is straightforward. The execution
trace captures every LLM interaction, every tool call, every delegation. You open the trace
in the visualizer and see exactly what happened.

When maintenance delegates to procurement, which delegates to logistics, which delegates
to compliance -- across four different Kubernetes pods in two different namespaces -- the
single-ensemble trace is not enough. Each ensemble has its own trace. The traces are
disconnected. The question "why did the parts order take 3 hours?" requires manually
correlating four separate traces.

The Ensemble Network addresses this with three observability layers: distributed tracing
(cross-ensemble correlation), adaptive auditing (dynamic logging depth), and scaling
metrics (operational visibility).

## Distributed Tracing: The Thread Through the Maze

### W3C Trace Context

Every cross-ensemble message carries W3C Trace Context headers. This is mandatory in the
wire protocol -- it is not optional, and it does not depend on whether the user has
deployed an OpenTelemetry collector.

```json
{
  "type": "task_request",
  "requestId": "maint-7721",
  "traceContext": {
    "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    "tracestate": "agentensemble=maintenance"
  }
}
```

The `traceparent` header carries a trace ID (shared across all participants in the
distributed trace) and a span ID (identifying this specific segment). When procurement
receives the request, it creates a new span as a child of maintenance's span. The trace
ID stays the same. When procurement delegates to logistics, the chain continues.

The result is a single distributed trace that spans the entire delegation chain:

```
Trace: 4bf92f3577b34da6a3ce929d0e0e4736
  |
  +-- maintenance: fix-boiler (23 min)
       |
       +-- procurement: purchase-parts (18 min)  [cross-ensemble]
            |
            +-- vendor-search tool (2 sec)
            +-- price-comparison tool (3 sec)
            +-- logistics: arrange-shipping (12 min)  [cross-ensemble]
                 |
                 +-- compliance: customs-clearance (8 min)  [cross-ensemble]
            +-- purchase-order tool (1 sec)
```

Open Jaeger (or Tempo, or Zipkin, or Datadog) and the full picture is there. Every
cross-ensemble boundary is visible. Every tool call within each ensemble is a child span.
The critical path -- where the time actually went -- is immediately obvious.

### OpenTelemetry Integration

The optional `agentensemble-telemetry-opentelemetry` module creates OTel spans at framework
boundaries:

| Span name | When created |
|---|---|
| `ensemble.run` | Root span for an ensemble execution |
| `task.execute` | Per-task child span |
| `llm.call` | Per-LLM-interaction (with token count attributes) |
| `tool.execute` | Per-tool-call child span |
| `network.delegate` | CLIENT span when calling another ensemble |
| `network.handle` | SERVER span when receiving a cross-ensemble request |

The CLIENT/SERVER span pair at ensemble boundaries is the standard OTel pattern for
distributed service calls. The CLIENT span is created by the `NetworkTask`/`NetworkTool`
in the calling ensemble. The SERVER span is created by the work request handler in the
receiving ensemble. They share the same trace ID and have a parent-child relationship.

Spans carry domain-specific attributes:

```
agentensemble.ensemble.name = "maintenance"
agentensemble.task.description = "Fix boiler in building 2"
agentensemble.agent.role = "Senior Maintenance Engineer"
agentensemble.delegation.target = "procurement"
agentensemble.tokens.input = 1842
agentensemble.tokens.output = 523
```

### ExecutionTrace Correlation

The existing `ExecutionTrace` (AgentEnsemble's native trace format) gains a `traceId`
field linking it to the distributed OTel trace. The visualizer can show: "This execution
trace is part of distributed trace `4bf92f...`. View in Jaeger." Clicking the link opens
the full distributed trace in the external trace viewer.

The `DelegationTrace` record (already used for agent-to-agent delegation within a single
ensemble) is extended with a `crossEnsemble` flag and the target ensemble name for
cross-ensemble delegations.

## Adaptive Audit Trail

### The Problem with Static Logging

Traditional logging uses static levels: DEBUG, INFO, WARN, ERROR. You set the level at
deployment time and it stays there. In production, you run at INFO to keep log volume
manageable. When something goes wrong, you wish you had DEBUG. You change the log level,
reproduce the issue, and analyze the output. By the time you have the detailed logs, the
original issue may have resolved itself.

For an always-on ensemble network, this is insufficient. The system runs continuously. The
"something going wrong" may be a transient issue during a load spike at 3am. You cannot
reproduce it on demand. You need the detailed logs at the moment the issue occurs, not
after you notice it.

### Leveled Auditing

The audit trail operates at four levels, conceptually similar to log levels but specific
to ensemble network operations:

| Level | What is recorded |
|---|---|
| `OFF` | Nothing (development/testing only) |
| `MINIMAL` | Cross-ensemble requests and responses: who called whom, when, result status |
| `STANDARD` | + human decisions, review gate outcomes, priority changes, directives |
| `FULL` | + LLM prompts and responses, tool inputs and outputs, memory reads and writes |

The default is MINIMAL. This captures the essential operational flow without the volume
of full LLM interaction logs.

### Dynamic Rules

Audit level can escalate automatically based on conditions:

```java
AuditPolicy policy = AuditPolicy.builder()
    .defaultLevel(AuditLevel.MINIMAL)

    // When the kitchen is under heavy load, capture more detail
    .rule(AuditRule.when("capacity_utilization > 0.8")
        .escalateTo(AuditLevel.STANDARD)
        .on("kitchen"))

    // When any task fails, capture everything for 10 minutes
    .rule(AuditRule.when("task_failed")
        .escalateTo(AuditLevel.FULL)
        .on("*")
        .duration(Duration.ofMinutes(10)))

    // When the manager is watching, capture more
    .rule(AuditRule.when("human_connected AND role == 'manager'")
        .escalateTo(AuditLevel.STANDARD)
        .on("*"))

    // During dinner rush, capture kitchen detail
    .rule(AuditRule.schedule("18:00-22:00")
        .escalateTo(AuditLevel.STANDARD)
        .on("kitchen", "room-service"))

    .build();
```

Four trigger types:

**Metric-driven**: Escalate when a metric crosses a threshold. "When queue depth exceeds
20" or "when capacity utilization exceeds 80%."

**Event-driven**: Escalate when a specific event occurs. "When a task fails" or "when a
circuit breaker opens."

**Schedule-driven**: Escalate during known high-activity periods. "During dinner rush
(6pm-10pm)" or "during the conference (Friday-Sunday)."

**Human-triggered**: Escalate when a human connects with a specific role. The manager
connecting to the dashboard automatically increases audit detail -- because if the manager
is watching, she probably wants to see more.

All escalations are **temporary**. They revert when the triggering condition clears (metric
drops, human disconnects) or when the specified duration expires (10 minutes after a task
failure). This prevents audit log volume from growing unbounded.

### Audit Sinks

The audit trail is written to a pluggable sink:

```java
.auditSink(AuditSink.log())            // SLF4J structured logging
.auditSink(AuditSink.database(ds))     // JDBC append-only table
.auditSink(AuditSink.eventStream())    // Kafka topic for downstream consumers
```

All records are immutable, append-only, timestamped, and correlatable via the distributed
trace ID. For regulated industries (finance, healthcare), the database sink provides the
tamper-evident audit trail that compliance requires.

## Scaling Metrics

Every ensemble exposes Prometheus/Micrometer metrics that serve two purposes: operational
dashboards (Grafana) and auto-scaling triggers (K8s HPA).

### Ensemble-Level Metrics

```
agentensemble_active_tasks{ensemble="kitchen"} 8
agentensemble_queued_requests{ensemble="kitchen"} 15
agentensemble_completed_tasks_total{ensemble="kitchen"} 1247
agentensemble_failed_tasks_total{ensemble="kitchen"} 3
agentensemble_capacity_utilization{ensemble="kitchen"} 0.85
agentensemble_max_concurrent{ensemble="kitchen"} 10
```

### Cross-Ensemble Metrics

```
agentensemble_network_requests_total{from="room-service",to="kitchen",task="prepare-meal"} 89
agentensemble_network_request_duration_seconds{from="room-service",to="kitchen",quantile="0.95"} 45.2
agentensemble_network_errors_total{from="room-service",to="kitchen",type="timeout"} 2
agentensemble_circuit_breaker_state{from="room-service",to="kitchen"} 0
```

### Token Cost Metrics

```
agentensemble_tokens_total{ensemble="kitchen",direction="input"} 284000
agentensemble_tokens_total{ensemble="kitchen",direction="output"} 91000
agentensemble_estimated_cost_dollars{ensemble="kitchen"} 12.50
```

Token costs are tracked per ensemble and exposed as gauges. The framework does not enforce
budgets in v3.0.0 -- cost control is achieved via control plane directives (Chapter 7) that
switch model tiers when spending exceeds thresholds. The metrics provide the visibility;
the human (or automated policy) makes the decision.

## The Observability Stack

The full observability picture for an ensemble network:

| Layer | Tool | What it shows |
|---|---|---|
| Distributed traces | Jaeger / Tempo | Cross-ensemble delegation chains, latency breakdown |
| Metrics dashboards | Grafana | Queue depths, capacity utilization, error rates, token spend |
| Audit trail | Database / Kafka | Who did what, when, why (compliance) |
| Live dashboard | agentensemble-viz | Real-time ensemble status, pending reviews, task flow |
| Log aggregation | ELK / Loki | Framework and application logs, correlated by trace ID |

Each layer serves a different audience and question:
- **Traces**: "Why did this specific request take so long?"
- **Metrics**: "Is the system healthy right now? Do we need to scale?"
- **Audit**: "Who approved this purchase order and when?"
- **Dashboard**: "What is happening right now across all departments?"
- **Logs**: "What was the exact error message when that task failed?"

The framework provides integration points for all of these. It does not replace any of
them. It generates the data (spans, metrics, audit records, events, logs) and lets the
user's existing infrastructure handle storage, querying, and visualization.
