package net.agentensemble.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.agentensemble.exception.AgentEnsembleException;
import org.junit.jupiter.api.Test;

class RateLimitInterruptedExceptionTest {

    @Test
    void testIsAgentEnsembleException() {
        var cause = new InterruptedException("test");
        var ex = new RateLimitInterruptedException(RateLimit.perMinute(60), cause);
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void testGetRateLimit() {
        var limit = RateLimit.perSecond(10);
        var cause = new InterruptedException();
        var ex = new RateLimitInterruptedException(limit, cause);
        assertThat(ex.getRateLimit()).isSameAs(limit);
    }

    @Test
    void testCauseIsPreserved() {
        var cause = new InterruptedException("interrupted");
        var ex = new RateLimitInterruptedException(RateLimit.perMinute(60), cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void testMessageContainsRateLimitInfo() {
        var limit = RateLimit.of(5, Duration.ofSeconds(1));
        var ex = new RateLimitInterruptedException(limit, new InterruptedException());
        assertThat(ex.getMessage()).contains("5");
    }

    @Test
    void testIsDistinctFromRateLimitTimeoutException() {
        var cause = new InterruptedException();
        var interrupted = new RateLimitInterruptedException(RateLimit.perSecond(1), cause);
        assertThat(interrupted).isNotInstanceOf(RateLimitTimeoutException.class);
    }
}
