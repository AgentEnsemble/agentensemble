package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThatNoException;

import net.agentensemble.web.protocol.Priority;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link QueueMetrics}.
 */
class QueueMetricsTest {

    @Test
    void noOp_doesNotThrow() {
        QueueMetrics noOp = QueueMetrics.noOp();
        assertThatNoException().isThrownBy(() -> noOp.recordQueueDepth("kitchen", Priority.NORMAL, 5));
    }

    @Test
    void noOp_returnsNonNull() {
        assertThatNoException().isThrownBy(() -> {
            QueueMetrics noOp = QueueMetrics.noOp();
            for (Priority p : Priority.values()) {
                noOp.recordQueueDepth("test", p, 0);
            }
        });
    }
}
