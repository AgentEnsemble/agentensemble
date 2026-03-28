package net.agentensemble.telemetry.otel;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;

/**
 * {@link EnsembleListener} that creates OpenTelemetry spans at framework boundaries.
 *
 * <p>Register via:
 * <pre>
 * Ensemble.builder()
 *     .listener(OTelTracingListener.create(otel))
 *     .build();
 * </pre>
 *
 * <p>Span types created by this listener:
 * <ul>
 *   <li>{@code ensemble.run} -- root span covering the entire ensemble execution</li>
 *   <li>{@code task.execute} -- one span per task execution</li>
 *   <li>{@code tool.execute} -- one span per tool call (created and ended immediately)</li>
 *   <li>{@code network.delegate} -- CLIENT-kind span for delegation handoffs</li>
 * </ul>
 *
 * <p>Thread safety: this class is thread-safe. Active spans are tracked in a
 * {@link ConcurrentHashMap} and the {@code traceId} field is volatile.
 */
public final class OTelTracingListener implements EnsembleListener {

    private static final String INSTRUMENTATION_SCOPE = "agentensemble";

    private final Tracer tracer;
    private final ConcurrentHashMap<String, Span> activeSpans = new ConcurrentHashMap<>();
    private volatile String traceId;

    private OTelTracingListener(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Create a new {@code OTelTracingListener} using the given {@link OpenTelemetry} instance.
     *
     * @param openTelemetry the OpenTelemetry instance to obtain a {@link Tracer} from
     * @return a new listener instance
     */
    public static OTelTracingListener create(OpenTelemetry openTelemetry) {
        Tracer tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        return new OTelTracingListener(tracer);
    }

    /**
     * Returns the trace ID of the root {@code ensemble.run} span, or {@code null} if no
     * ensemble has been started yet.
     *
     * @return the W3C trace ID hex string, or {@code null}
     */
    public String getTraceId() {
        return traceId;
    }

    @Override
    public void onEnsembleStarted(String ensembleId, String workflow, int totalTasks) {
        Span span = tracer.spanBuilder("ensemble.run")
                .setAttribute(OTelAttributes.ENSEMBLE_NAME, ensembleId)
                .setAttribute("agentensemble.workflow", workflow)
                .setAttribute("agentensemble.total_tasks", (long) totalTasks)
                .startSpan();
        activeSpans.put("ensemble:" + ensembleId, span);
        traceId = span.getSpanContext().getTraceId();
    }

    @Override
    public void onEnsembleCompleted(String ensembleId, Duration totalDuration, String exitReason) {
        Span span = activeSpans.remove("ensemble:" + ensembleId);
        if (span != null) {
            span.setAttribute(OTelAttributes.DURATION_MS, totalDuration.toMillis());
            span.setAttribute("agentensemble.exit_reason", exitReason);
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    @Override
    public void onTaskStart(TaskStartEvent event) {
        Span span = tracer.spanBuilder("task.execute")
                .setAttribute(OTelAttributes.TASK_DESCRIPTION, event.taskDescription())
                .setAttribute(OTelAttributes.AGENT_ROLE, event.agentRole())
                .setAttribute(OTelAttributes.TASK_INDEX, (long) event.taskIndex())
                .startSpan();
        activeSpans.put(taskKey(event.taskDescription(), event.agentRole()), span);
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        Span span = activeSpans.remove(taskKey(event.taskDescription(), event.agentRole()));
        if (span != null) {
            span.setAttribute(OTelAttributes.DURATION_MS, event.duration().toMillis());
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        Span span = activeSpans.remove(taskKey(event.taskDescription(), event.agentRole()));
        if (span != null) {
            String reason = event.cause() != null ? event.cause().getMessage() : "unknown";
            span.setStatus(StatusCode.ERROR, reason);
            span.end();
        }
    }

    @Override
    public void onToolCall(ToolCallEvent event) {
        Span span = tracer.spanBuilder("tool.execute")
                .setAttribute(OTelAttributes.TOOL_NAME, event.toolName())
                .setAttribute(OTelAttributes.AGENT_ROLE, event.agentRole())
                .setAttribute(OTelAttributes.DURATION_MS, event.duration().toMillis())
                .startSpan();
        span.end();
    }

    @Override
    public void onDelegationStarted(DelegationStartedEvent event) {
        Span span = tracer.spanBuilder("network.delegate")
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(OTelAttributes.DELEGATION_TARGET, event.workerRole())
                .setAttribute(OTelAttributes.TASK_DESCRIPTION, event.taskDescription())
                .startSpan();
        activeSpans.put("delegation:" + event.delegationId(), span);
    }

    @Override
    public void onDelegationCompleted(DelegationCompletedEvent event) {
        Span span = activeSpans.remove("delegation:" + event.delegationId());
        if (span != null) {
            span.setAttribute(OTelAttributes.DURATION_MS, event.duration().toMillis());
            span.setStatus(StatusCode.OK);
            span.end();
        }
    }

    @Override
    public void onDelegationFailed(DelegationFailedEvent event) {
        Span span = activeSpans.remove("delegation:" + event.delegationId());
        if (span != null) {
            span.setStatus(StatusCode.ERROR, event.failureReason());
            span.end();
        }
    }

    private static String taskKey(String description, String agentRole) {
        return "task:" + agentRole + ":" + description;
    }
}
