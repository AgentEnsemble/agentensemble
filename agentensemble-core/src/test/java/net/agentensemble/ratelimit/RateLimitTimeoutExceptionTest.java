package net.agentensemble.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.agentensemble.exception.AgentEnsembleException;
import org.junit.jupiter.api.Test;

class RateLimitTimeoutExceptionTest {

    @Test
    void testIsAgentEnsembleException() {
        var ex = new RateLimitTimeoutException(RateLimit.perMinute(60), Duration.ofSeconds(30));
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void testMessageContainsRateLimitAndTimeout() {
        var limit = RateLimit.perMinute(60);
        var timeout = Duration.ofSeconds(30);
        var ex = new RateLimitTimeoutException(limit, timeout);
        assertThat(ex.getMessage()).contains("60");
        assertThat(ex.getMessage()).contains("30");
    }

    @Test
    void testGetRateLimit() {
        var limit = RateLimit.perSecond(2);
        var ex = new RateLimitTimeoutException(limit, Duration.ofSeconds(10));
        assertThat(ex.getRateLimit()).isSameAs(limit);
    }

    @Test
    void testGetWaitTimeout() {
        var timeout = Duration.ofSeconds(45);
        var ex = new RateLimitTimeoutException(RateLimit.perMinute(10), timeout);
        assertThat(ex.getWaitTimeout()).isEqualTo(timeout);
    }

    @Test
    void testMessageContainsRateLimitDescription_perSecond() {
        var ex = new RateLimitTimeoutException(RateLimit.perSecond(2), Duration.ofSeconds(5));
        assertThat(ex.getMessage()).contains("2");
    }
}
