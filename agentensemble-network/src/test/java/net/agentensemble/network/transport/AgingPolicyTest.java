package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgingPolicy}.
 */
class AgingPolicyTest {

    @Test
    void every_createsPolicy() {
        AgingPolicy policy = AgingPolicy.every(Duration.ofMinutes(30));
        assertThat(policy.promotionInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void none_createsLargeInterval() {
        AgingPolicy policy = AgingPolicy.none();
        assertThat(policy.promotionInterval()).isGreaterThan(Duration.ofDays(365));
    }

    @Test
    void constructor_nullInterval_throwsNPE() {
        assertThatThrownBy(() -> new AgingPolicy(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_negativeInterval_throwsIAE() {
        assertThatThrownBy(() -> new AgingPolicy(Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void constructor_zeroInterval_throwsIAE() {
        assertThatThrownBy(() -> new AgingPolicy(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void every_nullInterval_throwsNPE() {
        assertThatThrownBy(() -> AgingPolicy.every(null)).isInstanceOf(NullPointerException.class);
    }
}
