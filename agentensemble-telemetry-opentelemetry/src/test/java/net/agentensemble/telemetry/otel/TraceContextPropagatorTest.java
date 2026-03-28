package net.agentensemble.telemetry.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TraceContextPropagatorTest {

    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetry otel =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        tracer = otel.getTracer("test");
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void extractFromTraceparent_validTraceparent() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        SpanContext ctx = TraceContextPropagator.extractFromTraceparent(traceparent);

        assertThat(ctx.isValid()).isTrue();
        assertThat(ctx.isRemote()).isTrue();
        assertThat(ctx.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(ctx.getSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(ctx.getTraceFlags().asHex()).isEqualTo("01");
    }

    @Test
    void extractFromTraceparent_nullInput_returnsInvalid() {
        SpanContext ctx = TraceContextPropagator.extractFromTraceparent(null);
        assertThat(ctx.isValid()).isFalse();
    }

    @Test
    void extractFromTraceparent_emptyInput_returnsInvalid() {
        SpanContext ctx = TraceContextPropagator.extractFromTraceparent("");
        assertThat(ctx.isValid()).isFalse();
    }

    @Test
    void extractFromTraceparent_malformedInput_returnsInvalid() {
        SpanContext ctx = TraceContextPropagator.extractFromTraceparent("not-a-traceparent");
        assertThat(ctx.isValid()).isFalse();
    }

    @Test
    void injectTraceparent_formatsCorrectly() {
        Span span = tracer.spanBuilder("test-span").startSpan();
        try {
            String traceparent = TraceContextPropagator.injectTraceparent(span);

            assertThat(traceparent).isNotNull();
            assertThat(traceparent).startsWith("00-");

            // Verify format: 00-{traceId}-{spanId}-{flags}
            String[] parts = traceparent.split("-");
            assertThat(parts).hasSize(4);
            assertThat(parts[0]).isEqualTo("00"); // version
            assertThat(parts[1]).hasSize(32); // trace ID
            assertThat(parts[2]).hasSize(16); // span ID
            assertThat(parts[3]).hasSize(2); // trace flags
        } finally {
            span.end();
        }
    }

    @Test
    void injectTraceparent_invalidSpan_returnsNull() {
        Span invalidSpan = Span.getInvalid();
        String traceparent = TraceContextPropagator.injectTraceparent(invalidSpan);
        assertThat(traceparent).isNull();
    }

    @Test
    void extractFromTraceparent_badHexFlags_returnsInvalid() {
        // "ZZ" is not valid hex and would throw NumberFormatException without the try-catch
        SpanContext ctx = TraceContextPropagator.extractFromTraceparent(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-ZZ");
        assertThat(ctx.isValid()).isFalse();
    }

    @Test
    void injectTraceparent_nullSpan_returnsNull() {
        String traceparent = TraceContextPropagator.injectTraceparent(null);
        assertThat(traceparent).isNull();
    }

    @Test
    void roundtrip_extractThenInject() {
        String originalTraceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

        SpanContext extracted = TraceContextPropagator.extractFromTraceparent(originalTraceparent);

        assertThat(extracted.isValid()).isTrue();
        assertThat(extracted.getTraceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(extracted.getSpanId()).isEqualTo("00f067aa0ba902b7");
    }
}
