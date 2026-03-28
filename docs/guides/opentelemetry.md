# OpenTelemetry Integration

The `agentensemble-telemetry-opentelemetry` module creates OpenTelemetry spans at
framework boundaries for distributed tracing.

---

## Setup

### Dependency

```kotlin
dependencies {
    implementation("net.agentensemble:agentensemble-telemetry-opentelemetry:{{ae_version}}")
    implementation("io.opentelemetry:opentelemetry-api:1.47.0")
}
```

### Registration

```java
import net.agentensemble.telemetry.otel.OTelTracingListener;

OpenTelemetry otel = // configure your OTel SDK
OTelTracingListener listener = OTelTracingListener.create(otel);

Ensemble.builder()
    .chatLanguageModel(model)
    .listener(listener)
    .task(Task.of("Research AI trends"))
    .build()
    .run();

// After run, the trace ID is available
String traceId = listener.getTraceId();
```

---

## Span Types

| Span | When | Kind |
|------|------|------|
| `ensemble.run` | Root span for an ensemble execution | INTERNAL |
| `task.execute` | Per-task child span | INTERNAL |
| `tool.execute` | Per-tool-call child span | INTERNAL |
| `network.delegate` | When calling another ensemble | CLIENT |

---

## Span Attributes

All spans carry AgentEnsemble-specific attributes:

| Attribute | Description |
|-----------|-------------|
| `agentensemble.ensemble.name` | Ensemble ID |
| `agentensemble.task.description` | Task description |
| `agentensemble.agent.role` | Agent role |
| `agentensemble.delegation.target` | Target worker role |
| `agentensemble.tool.name` | Tool name |
| `agentensemble.task.index` | Task index (1-based) |
| `agentensemble.duration_ms` | Duration in milliseconds |

---

## W3C Trace Context Propagation

Cross-ensemble requests carry W3C trace context via the `TraceContextPropagator`:

```java
// Extract trace context from incoming WorkRequest
SpanContext remoteCtx = TraceContextPropagator.extractFromTraceparent(
    request.traceContext().traceparent());

// Inject trace context into outgoing request
String traceparent = TraceContextPropagator.injectTraceparent(currentSpan);
```

---

## ExecutionTrace Correlation

When `OTelTracingListener` is registered, the `ExecutionTrace.traceId` field is
populated with the OTel trace ID. This allows linking the AgentEnsemble trace to
the distributed trace in your external viewer (Jaeger, Grafana Tempo, etc.).

```java
EnsembleOutput output = ensemble.run();
String traceId = output.getTrace().getTraceId();
// -> "4bf92f3577b34da6a3ce929d0e0e4736"
```

---

## Backend Configuration

The module is backend-agnostic. Configure your OTel SDK to export to your
preferred backend: Jaeger, Grafana Tempo, Zipkin, Datadog, etc.
