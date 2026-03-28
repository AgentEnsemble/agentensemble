package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TraceContext}.
 */
class TraceContextTest {

    @Test
    void constructionWithAllFields() {
        TraceContext ctx =
                new TraceContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", "congo=t61rcWkgMzE");
        assertThat(ctx.traceparent()).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(ctx.tracestate()).isEqualTo("congo=t61rcWkgMzE");
    }

    @Test
    void nullTracestateIsAllowed() {
        TraceContext ctx = new TraceContext("00-abcdef1234567890abcdef1234567890-1234567890abcdef-01", null);
        assertThat(ctx.traceparent()).isEqualTo("00-abcdef1234567890abcdef1234567890-1234567890abcdef-01");
        assertThat(ctx.tracestate()).isNull();
    }

    @Test
    void equality() {
        TraceContext a = new TraceContext("00-trace-span-01", "key=value");
        TraceContext b = new TraceContext("00-trace-span-01", "key=value");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
