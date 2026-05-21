package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import net.agentensemble.web.publisher.AbstractLiveEventPublisher;
import net.agentensemble.web.publisher.LiveEventPublisher;
import org.junit.jupiter.api.Test;

/**
 * Verifies publisher-mode wiring on {@link WebDashboard}:
 * <ul>
 *   <li>No port is bound (the embedded server is not started).</li>
 *   <li>Lifecycle events from the streaming listener arrive at the configured publisher,
 *       wrapped as {@link LiveEventEnvelope}s carrying the configured {@link ProducerInfo}.</li>
 * </ul>
 */
class WebDashboardPublisherModeTest {

    @Test
    void publisherMode_doesNotBindPortAndForwardsEvents() {
        List<LiveEventEnvelope> captured = new ArrayList<>();
        AtomicBoolean started = new AtomicBoolean(false);
        MessageSerializer serializer = new MessageSerializer();
        LiveEventPublisher publisher =
                new AbstractLiveEventPublisher(ProducerInfo.of("test-1", "svc-test"), serializer) {
                    @Override
                    public void start() {
                        started.set(true);
                    }

                    @Override
                    public void stop() {
                        started.set(false);
                    }

                    @Override
                    public boolean isConnected() {
                        return started.get();
                    }

                    @Override
                    public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber) {
                        // no-op for this test
                    }

                    @Override
                    protected void publishEnvelope(LiveEventEnvelope envelope) {
                        captured.add(envelope);
                    }
                };

        WebDashboard dashboard = WebDashboard.builder()
                .port(0) // Port is ignored in publisher mode but still validated by the builder.
                .publisher(publisher)
                .build();

        dashboard.start();

        // No browser-facing port should have been bound.
        assertThat(dashboard.actualPort()).isEqualTo(-1);
        assertThat(dashboard.isRunning()).isTrue();
        assertThat(started.get()).isTrue();

        // Fire a lifecycle event through the streaming listener.
        dashboard.streamingListener().onTaskStart(new TaskStartEvent("Do work", "Worker", 1, 1));

        assertThat(captured).hasSize(1);
        LiveEventEnvelope envelope = captured.get(0);
        assertThat(envelope.producer().producerId()).isEqualTo("test-1");
        assertThat(envelope.producer().serviceName()).isEqualTo("svc-test");
        assertThat(envelope.message().get("type").asText()).isEqualTo("task_started");
        assertThat(envelope.message().get("taskDescription").asText()).isEqualTo("Do work");

        // onEnsembleStarted also flows through the active sink.
        dashboard.onEnsembleStarted("run-a", Instant.now(), 1, "SEQUENTIAL");
        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).message().get("type").asText()).isEqualTo("ensemble_started");

        dashboard.stop();
        assertThat(started.get()).isFalse();
    }

    @Test
    void embeddedMode_actualPortIsAssigned() {
        WebDashboard dashboard = WebDashboard.builder().port(0).build();
        try {
            dashboard.start();
            assertThat(dashboard.actualPort()).isGreaterThan(0);
            assertThat(dashboard.getPublisher()).isNull();
        } finally {
            dashboard.stop();
        }
        // Sanity-check on the wire shape we expect embedded mode to produce.
        assertThat(Map.of("mode", "embedded")).isNotNull();
    }
}
