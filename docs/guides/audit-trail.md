# Leveled Audit Trail

AgentEnsemble v3.0.0 provides a leveled audit trail with dynamic rule-based
escalation for production observability and compliance.

---

## Audit Levels

| Level | What is recorded |
|-------|-----------------|
| `OFF` | Nothing (dev/test) |
| `MINIMAL` | Cross-ensemble delegation events only |
| `STANDARD` | + task start/complete/fail, review decisions |
| `FULL` | + tool I/O, LLM token events |

---

## Configuration

```java
Ensemble.builder()
    .chatLanguageModel(model)
    .auditPolicy(AuditPolicy.builder()
        .defaultLevel(AuditLevel.STANDARD)
        .build())
    .auditSink(AuditSink.log())
    .task(Task.of("Process payment"))
    .build()
    .run();
```

---

## Dynamic Escalation Rules

Audit level can escalate based on conditions:

```java
AuditPolicy policy = AuditPolicy.builder()
    .defaultLevel(AuditLevel.MINIMAL)
    .rule(AuditRule.when("task_failed")
        .escalateTo(AuditLevel.FULL)
        .on("*")
        .duration(Duration.ofMinutes(10))
        .build())
    .build();
```

When a task fails, audit level escalates to FULL for 10 minutes, then reverts
to MINIMAL automatically.

---

## Audit Sinks

### SLF4J Logging

```java
.auditSink(AuditSink.log())
```

Records are written as structured log entries with MDC context:
- `audit.level` -- the audit level
- `audit.category` -- event category (e.g., `task.start`, `tool.call`)
- `audit.ensemble` -- ensemble ID
- `audit.traceId` -- correlation trace ID

---

## Audit Records

All audit records are immutable and contain:

| Field | Description |
|-------|-------------|
| `timestamp` | When the event occurred |
| `level` | Audit level at the time of recording |
| `ensembleId` | Which ensemble produced the event |
| `category` | Event category (e.g., `task.start`) |
| `summary` | Human-readable summary |
| `traceId` | Correlation ID for distributed tracing |
| `details` | Immutable map of event-specific details |
