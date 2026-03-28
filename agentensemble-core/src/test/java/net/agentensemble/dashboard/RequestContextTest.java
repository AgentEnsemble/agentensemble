package net.agentensemble.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestContext}.
 */
class RequestContextTest {

    @Test
    void constructor_allFields() {
        RequestContext ctx = new RequestContext("req-1", "cache-key-1", "USE_CACHED", Duration.ofMinutes(5));

        assertThat(ctx.requestId()).isEqualTo("req-1");
        assertThat(ctx.cacheKey()).isEqualTo("cache-key-1");
        assertThat(ctx.cachePolicy()).isEqualTo("USE_CACHED");
        assertThat(ctx.maxAge()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void constructor_nullableFields() {
        RequestContext ctx = new RequestContext(null, null, null, null);

        assertThat(ctx.requestId()).isNull();
        assertThat(ctx.cacheKey()).isNull();
        assertThat(ctx.cachePolicy()).isNull();
        assertThat(ctx.maxAge()).isNull();
    }
}
