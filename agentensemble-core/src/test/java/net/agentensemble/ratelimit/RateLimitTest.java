package net.agentensemble.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

class RateLimitTest {

    // ========================
    // Factory: of(int, Duration)
    // ========================

    @Test
    void testOf_createsRateLimit() {
        var limit = RateLimit.of(60, Duration.ofMinutes(1));
        assertThat(limit.getRequests()).isEqualTo(60);
        assertThat(limit.getPeriod()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void testOf_withZeroRequests_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.of(0, Duration.ofMinutes(1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requests");
    }

    @Test
    void testOf_withNegativeRequests_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.of(-1, Duration.ofMinutes(1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("requests");
    }

    @Test
    void testOf_withNullPeriod_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.of(10, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("period");
    }

    @Test
    void testOf_withZeroPeriod_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.of(10, Duration.ZERO))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("period");
    }

    @Test
    void testOf_withNegativePeriod_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.of(10, Duration.ofSeconds(-1)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("period");
    }

    // ========================
    // Factory: perMinute(int)
    // ========================

    @Test
    void testPerMinute_setsCorrectPeriod() {
        var limit = RateLimit.perMinute(60);
        assertThat(limit.getRequests()).isEqualTo(60);
        assertThat(limit.getPeriod()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void testPerMinute_withOne_succeeds() {
        var limit = RateLimit.perMinute(1);
        assertThat(limit.getRequests()).isEqualTo(1);
    }

    @Test
    void testPerMinute_withZero_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.perMinute(0)).isInstanceOf(ValidationException.class);
    }

    // ========================
    // Factory: perSecond(int)
    // ========================

    @Test
    void testPerSecond_setsCorrectPeriod() {
        var limit = RateLimit.perSecond(2);
        assertThat(limit.getRequests()).isEqualTo(2);
        assertThat(limit.getPeriod()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void testPerSecond_withOne_succeeds() {
        var limit = RateLimit.perSecond(1);
        assertThat(limit.getRequests()).isEqualTo(1);
    }

    @Test
    void testPerSecond_withZero_throwsValidation() {
        assertThatThrownBy(() -> RateLimit.perSecond(0)).isInstanceOf(ValidationException.class);
    }

    // ========================
    // Equality / hashCode
    // ========================

    @Test
    void testEquals_sameLimits_areEqual() {
        var a = RateLimit.of(60, Duration.ofMinutes(1));
        var b = RateLimit.of(60, Duration.ofMinutes(1));
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void testEquals_differentRequests_areNotEqual() {
        var a = RateLimit.perMinute(60);
        var b = RateLimit.perMinute(30);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void testEquals_differentPeriods_areNotEqual() {
        var a = RateLimit.of(10, Duration.ofSeconds(1));
        var b = RateLimit.of(10, Duration.ofMinutes(1));
        assertThat(a).isNotEqualTo(b);
    }

    // ========================
    // toString
    // ========================

    @Test
    void testToString_isInformative() {
        var limit = RateLimit.perMinute(60);
        var str = limit.toString();
        assertThat(str).contains("60");
    }

    // ========================
    // nanosPerToken (internal)
    // ========================

    @Test
    void testNanosPerToken_perMinute60() {
        var limit = RateLimit.perMinute(60);
        // 60 requests per 60 seconds = 1 request/sec = 1_000_000_000 ns/token
        assertThat(limit.nanosPerToken()).isEqualTo(1_000_000_000L);
    }

    @Test
    void testNanosPerToken_perSecond2() {
        var limit = RateLimit.perSecond(2);
        // 2 requests per second = 500_000_000 ns/token
        assertThat(limit.nanosPerToken()).isEqualTo(500_000_000L);
    }
}
