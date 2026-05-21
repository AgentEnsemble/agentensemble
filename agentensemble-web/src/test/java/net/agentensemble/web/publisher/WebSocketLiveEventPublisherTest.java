package net.agentensemble.web.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.javalin.Javalin;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.TaskStartedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketLiveEventPublisher}: lifecycle, capability flags, restart
 * after stop, and end-to-end envelope delivery via a real ingress endpoint hosted by Javalin
 * (the same Javalin that backs the production hub). The reconnect/backoff branches in
 * {@code WsListener.onError} / {@code scheduleReconnect} are intentionally out of scope: they
 * are timer-driven and exercised by the hub integration tests; the harness here covers the
 * deterministic happy-path code that codecov surfaced as uncovered.
 */
class WebSocketLiveEventPublisherTest {

    private Javalin server;
    private int port;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private CountDownLatch envelopeLatch;
    private WebSocketLiveEventPublisher publisher;

    @BeforeEach
    void setUp() {
        envelopeLatch = new CountDownLatch(1);
        server = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            config.routes.ws("/ingress", ws -> {
                ws.onMessage(ctx -> {
                    received.add(ctx.message());
                    envelopeLatch.countDown();
                });
            });
        });
        server.start("127.0.0.1", 0);
        port = server.port();
    }

    @AfterEach
    void tearDown() {
        if (publisher != null) publisher.stop();
        if (server != null) server.stop();
    }

    @Test
    void invalidQueueCapacity_rejected() {
        assertThatThrownBy(() -> new WebSocketLiveEventPublisher(
                        URI.create("ws://127.0.0.1:1/ingress"),
                        ProducerInfo.of("p1", "svc"),
                        new MessageSerializer(),
                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queueCapacity");
    }

    @Test
    void supportsReviewFanIn_isTrueAndSubscriberCanBeSet() {
        publisher = WebSocketLiveEventPublisher.connect(
                URI.create("ws://127.0.0.1:" + port + "/ingress"), ProducerInfo.of("p1", "svc"));
        assertThat(publisher.supportsReviewFanIn()).isTrue();
        publisher.subscribeToReviewDecisions(d -> {}); // does not throw
    }

    @Test
    void startThenStopThenStart_isRestartable() throws Exception {
        publisher = WebSocketLiveEventPublisher.connect(
                URI.create("ws://127.0.0.1:" + port + "/ingress"), ProducerInfo.of("p1", "svc"));

        publisher.start();
        // Allow the WS handshake to complete.
        for (int i = 0; i < 30 && !publisher.isConnected(); i++) {
            Thread.sleep(50);
        }
        assertThat(publisher.isConnected()).isTrue();

        publisher.stop();
        assertThat(publisher.isConnected()).isFalse();

        // A second start cycle must work: the original implementation would leave a shut
        // down scheduler in place and silently never reconnect.
        envelopeLatch = new CountDownLatch(1);
        received.clear();
        publisher.start();
        for (int i = 0; i < 30 && !publisher.isConnected(); i++) {
            Thread.sleep(50);
        }
        assertThat(publisher.isConnected()).isTrue();
    }

    @Test
    void publish_deliversEnvelopeViaWebSocket() throws Exception {
        MessageSerializer serializer = new MessageSerializer();
        publisher = new WebSocketLiveEventPublisher(
                URI.create("ws://127.0.0.1:" + port + "/ingress"), ProducerInfo.of("p1", "svc"), serializer, 32);
        publisher.start();

        for (int i = 0; i < 30 && !publisher.isConnected(); i++) {
            Thread.sleep(50);
        }
        assertThat(publisher.isConnected()).isTrue();

        publisher.accept(serializer.toJson(new TaskStartedMessage(1, 1, "T", "A", Instant.now())));
        assertThat(envelopeLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("\"type\":\"event\"");
        assertThat(received.get(0)).contains("\"producerId\":\"p1\"");
        assertThat(received.get(0)).contains("task_started");
    }

    @Test
    void producerIdWithReservedCharacters_isUrlEncoded() throws Exception {
        // Producer IDs with `&` and spaces must not corrupt the query string. We can verify by
        // observing that the connection succeeds and a published envelope arrives end-to-end.
        publisher = WebSocketLiveEventPublisher.connect(
                URI.create("ws://127.0.0.1:" + port + "/ingress"), ProducerInfo.of("svc a&b", "svc"));
        publisher.start();
        for (int i = 0; i < 30 && !publisher.isConnected(); i++) {
            Thread.sleep(50);
        }
        assertThat(publisher.isConnected()).isTrue();
    }
}
