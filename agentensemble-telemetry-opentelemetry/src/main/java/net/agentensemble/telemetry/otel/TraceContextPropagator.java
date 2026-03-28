package net.agentensemble.telemetry.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;

/**
 * Utility for extracting and injecting W3C trace context (traceparent header)
 * for cross-ensemble distributed tracing.
 *
 * <p>This class handles the W3C Trace Context format:
 * {@code version-traceId-spanId-traceFlags} (e.g., {@code "00-abc123...-def456...-01"}).
 *
 * <p>Use {@link #extractFromTraceparent(String)} to parse an incoming traceparent
 * header into a {@link SpanContext}, and {@link #injectTraceparent(Span)} to format
 * the current span context as a traceparent header for outgoing requests.
 */
public final class TraceContextPropagator {

    private TraceContextPropagator() {}

    /**
     * Extract trace ID and span ID from a W3C traceparent header value.
     *
     * <p>Format: {@code version-traceId-spanId-traceFlags}
     * (e.g., {@code "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"}).
     *
     * @param traceparent the traceparent header value; may be {@code null} or empty
     * @return a remote {@link SpanContext}, or {@link SpanContext#getInvalid()} if
     *         the input is null, empty, or malformed
     */
    public static SpanContext extractFromTraceparent(String traceparent) {
        if (traceparent == null || traceparent.isEmpty()) {
            return SpanContext.getInvalid();
        }
        try {
            String[] parts = traceparent.split("-");
            if (parts.length < 4) {
                return SpanContext.getInvalid();
            }
            String traceId = parts[1];
            String spanId = parts[2];
            byte flags = Byte.parseByte(parts[3], 16);
            return SpanContext.createFromRemoteParent(
                    traceId, spanId, TraceFlags.fromByte(flags), TraceState.getDefault());
        } catch (Exception e) {
            return SpanContext.getInvalid();
        }
    }

    /**
     * Format the current span context as a W3C traceparent header value.
     *
     * @param span the span whose context to format
     * @return the traceparent header value, or {@code null} if the span context is invalid
     */
    public static String injectTraceparent(Span span) {
        if (span == null) {
            return null;
        }
        SpanContext ctx = span.getSpanContext();
        if (!ctx.isValid()) {
            return null;
        }
        return String.format(
                "00-%s-%s-%s",
                ctx.getTraceId(), ctx.getSpanId(), ctx.getTraceFlags().asHex());
    }
}
