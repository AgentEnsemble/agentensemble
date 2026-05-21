package net.agentensemble.web.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.EnsembleStartedMessage;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.TaskStartedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives a real WebSocket client against the hub's {@code /ingress} endpoint to cover the
 * {@code handleIngressConnect}, {@code handleIngressMessage}, and {@code handleIngressClose}
 * paths in {@link LiveEventHub} that the in-memory-publisher tests do not exercise.
 */
class LiveEventHubWsIngressTest {

    private LiveEventHub hub;
    private final MessageSerializer serializer = new MessageSerializer();

    @BeforeEach
    void setUp() {
        hub = LiveEventHub.builder().port(0).host("127.0.0.1").build();
        hub.start();
    }

    @AfterEach
    void tearDown() {
        if (hub != null) hub.stop();
    }

    @Test
    void wsIngress_registersProducerAndForwardsEnvelopes() throws Exception {
        WebSocket publisher = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + hub.actualPort() + "/ingress"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        ProducerInfo info = ProducerInfo.of("ws-p1", "svc");
        LiveEventEnvelope envelope = new LiveEventEnvelope(
                info,
                1,
                Instant.parse("2026-05-21T10:00:00Z"),
                serializer.toJsonNode(serializer.toJson(
                        new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL"))));
        publisher.sendText(serializer.toJson(envelope), true).get(5, TimeUnit.SECONDS);

        // Wait for the registry to absorb the registration.
        for (int i = 0; i < 30 && hub.producers().find("ws-p1") == null; i++) {
            Thread.sleep(50);
        }
        assertThat(hub.producers().find("ws-p1")).isNotNull();
        assertThat(hub.producers().find("ws-p1").info().serviceName()).isEqualTo("svc");

        // Send a second envelope on the same context — exercises the "existing channel
        // matches ctx" fast path.
        LiveEventEnvelope envelope2 = new LiveEventEnvelope(
                info,
                2,
                Instant.parse("2026-05-21T10:00:01Z"),
                serializer.toJsonNode(serializer.toJson(
                        new TaskStartedMessage(1, 1, "T", "A", Instant.parse("2026-05-21T10:00:01Z")))));
        publisher.sendText(serializer.toJson(envelope2), true).get(5, TimeUnit.SECONDS);

        // Close the publisher — exercises handleIngressClose, marking the producer inactive
        // and broadcasting producer_left.
        publisher.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        for (int i = 0; i < 30 && hub.producers().find("ws-p1").isActive(); i++) {
            Thread.sleep(50);
        }
        assertThat(hub.producers().find("ws-p1").isActive()).isFalse();
    }

    @Test
    void wsIngress_dropsMalformedEnvelopeWithoutFailingSession() throws Exception {
        WebSocket publisher = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + hub.actualPort() + "/ingress"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        // Drop a junk frame — the hub logs and skips, the session stays open.
        publisher.sendText("not json", true).get(5, TimeUnit.SECONDS);

        // Follow with a valid envelope to verify the session still works.
        ProducerInfo info = ProducerInfo.of("ws-p2", "svc");
        LiveEventEnvelope envelope = new LiveEventEnvelope(
                info,
                1,
                Instant.parse("2026-05-21T10:00:00Z"),
                serializer.toJsonNode(serializer.toJson(
                        new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL"))));
        publisher.sendText(serializer.toJson(envelope), true).get(5, TimeUnit.SECONDS);

        for (int i = 0; i < 30 && hub.producers().find("ws-p2") == null; i++) {
            Thread.sleep(50);
        }
        assertThat(hub.producers().find("ws-p2")).isNotNull();
        publisher.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    @Test
    void wsIngress_reconnectWithSameProducerIdRebindsChannel() throws Exception {
        ProducerInfo info = ProducerInfo.of("ws-p3", "svc");
        LiveEventEnvelope envelope = new LiveEventEnvelope(
                info,
                1,
                Instant.parse("2026-05-21T10:00:00Z"),
                serializer.toJsonNode(serializer.toJson(
                        new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL"))));

        WebSocket pub1 = open(envelope);
        for (int i = 0; i < 30 && hub.producers().find("ws-p3") == null; i++) {
            Thread.sleep(50);
        }
        pub1.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);

        // Reconnect with a fresh WS context but the same producerId. The hub should rebind
        // the channel rather than create a duplicate producer.
        WebSocket pub2 = open(envelope);
        pub2.sendText(serializer.toJson(envelope), true).get(5, TimeUnit.SECONDS);
        for (int i = 0; i < 30 && !hub.producers().find("ws-p3").isActive(); i++) {
            Thread.sleep(50);
        }
        assertThat(hub.producers().find("ws-p3").isActive()).isTrue();
        // Still exactly one producer with that id.
        assertThat(hub.producers().listProducers())
                .filteredOn(p -> "ws-p3".equals(p.producerId()))
                .hasSize(1);
        pub2.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    private WebSocket open(LiveEventEnvelope firstEnvelope) throws Exception {
        WebSocket ws = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + hub.actualPort() + "/ingress"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        ws.sendText(serializer.toJson(firstEnvelope), true).get(5, TimeUnit.SECONDS);
        return ws;
    }
}
