package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueueStatus}.
 */
class QueueStatusTest {

    @Test
    void record_storesValues() {
        QueueStatus status = new QueueStatus(3, Duration.ofMinutes(5));
        assertThat(status.queuePosition()).isEqualTo(3);
        assertThat(status.estimatedCompletion()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void positionZero_isValid() {
        QueueStatus status = new QueueStatus(0, Duration.ZERO);
        assertThat(status.queuePosition()).isZero();
    }

    @Test
    void negativePosition_throwsIAE() {
        assertThatThrownBy(() -> new QueueStatus(-1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void nullEstimatedCompletion_throwsNPE() {
        assertThatThrownBy(() -> new QueueStatus(0, null)).isInstanceOf(NullPointerException.class);
    }
}
