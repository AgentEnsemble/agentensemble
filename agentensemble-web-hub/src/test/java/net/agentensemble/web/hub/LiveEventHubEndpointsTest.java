package net.agentensemble.web.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.EnsembleStartedMessage;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import net.agentensemble.web.protocol.ReviewRequestedMessage;
import net.agentensemble.web.protocol.TaskStartedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the {@link LiveEventHub} REST/WS endpoint surface that the existing tests don't
 * exercise: HTTP ingress (`POST /api/hub/ingress`), the producer-roster REST endpoint, the
 * browser unknown-reviewId case, lifecycle (actualPort returns -1 when stopped), and the
 * eviction sweep.
 */
class LiveEventHubEndpointsTest {

    private LiveEventHub hub;
    private final MessageSerializer serializer = new MessageSerializer();

    @BeforeEach
    void setUp() {
        hub = LiveEventHub.builder()
                .port(0)
                .host("127.0.0.1")
                .evictionIdleAfter(Duration.ofMillis(10))
                .build();
        hub.start();
    }

    @AfterEach
    void tearDown() {
        if (hub != null) hub.stop();
    }

    @Test
    void actualPortIsMinusOneAfterStop() {
        assertThat(hub.actualPort()).isGreaterThan(0);
        hub.stop();
        assertThat(hub.actualPort()).isEqualTo(-1);
    }

    @Test
    void actualPortIsMinusOneBeforeStart() {
        LiveEventHub other = LiveEventHub.builder().port(0).build();
        assertThat(other.actualPort()).isEqualTo(-1);
    }

    @Test
    void postIngress_acceptsAndIngestsEnvelopeJson() throws Exception {
        ProducerInfo info = ProducerInfo.of("p1", "svc");
        LiveEventEnvelope envelope = new LiveEventEnvelope(
                info,
                1,
                Instant.parse("2026-05-21T10:00:00Z"),
                serializer.toJsonNode(serializer.toJson(
                        new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL"))));
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + hub.actualPort() + "/api/hub/ingress"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(serializer.toJson(envelope)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);

        // The producer should now appear in the registry.
        assertThat(hub.producers().listProducers())
                .extracting(ProducerInfo::producerId)
                .contains("p1");
    }

    @Test
    void postIngress_rejectsBlankBody() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + hub.actualPort() + "/api/hub/ingress"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void postIngress_rejectsMalformedEnvelope() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + hub.actualPort() + "/api/hub/ingress"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"event\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void getProducers_returnsKnownProducers() throws Exception {
        InMemoryLiveEventPublisher p = new InMemoryLiveEventPublisher(
                hub, new ProducerInfo("p1", "svc", "i1", "h1", "v1", java.util.Map.of("env", "prod")), serializer);
        p.start();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + hub.actualPort() + "/api/hub/producers"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"p1\"");
        assertThat(response.body()).contains("\"svc\"");
        assertThat(response.body()).contains("\"i1\"");
        assertThat(response.body()).contains("\"v1\"");
        assertThat(response.body()).contains("\"env\":\"prod\"");
        p.stop();
    }

    @Test
    void browser_unknownReviewIdIsIgnored() throws Exception {
        // Send a review_decision for an unknown reviewId; the hub should swallow it without
        // affecting state or throwing.
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        WebSocket browser = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + hub.actualPort() + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            received.add(buf.toString());
                            if (buf.toString().contains("hub_hello")) helloLatch.countDown();
                            buf.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        browser.sendText(serializer.toJson(new ReviewDecisionMessage("never-existed", "continue", null)), true);
        // Send a second non-review message so we have a synchronization point; the hub should
        // still be alive and processing.
        browser.sendText("{\"type\":\"ping\"}", true);
        Thread.sleep(100);
        // No assertions on hub state — the test passes if the hub did not throw or hang.
        browser.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    @Test
    void browser_malformedDecisionIsLoggedAndIgnored() throws Exception {
        WebSocket browser = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + hub.actualPort() + "/ws"), new WebSocket.Listener() {
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
        browser.sendText("not json", true);
        browser.sendText("{\"type\":\"review_decision\"}", true); // missing reviewId
        Thread.sleep(100);
        browser.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
    }

    @Test
    void evictionSweep_dropsLongIdleProducer() throws InterruptedException {
        InMemoryLiveEventPublisher p = new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        p.start();
        // Send one envelope so the producer exists in the registry.
        p.accept(serializer.toJson(new TaskStartedMessage(1, 1, "t", "a", Instant.now())));
        assertThat(hub.producers().listProducers())
                .extracting(ProducerInfo::producerId)
                .contains("p1");
        p.stop();
        // The hub was built with evictionIdleAfter = 10ms; the eviction sweep runs every
        // 60s in production. We trigger it directly by invoking evictIdle through the
        // ProducerRegistry to keep the test fast.
        Thread.sleep(30);
        java.util.List<String> evicted = new java.util.ArrayList<>();
        hub.producers().evictIdle(Duration.ofMillis(10), (id, reason) -> evicted.add(id));
        assertThat(evicted).contains("p1");
    }

    @Test
    void doubleStart_isIdempotent() {
        int firstPort = hub.actualPort();
        hub.start();
        // Second start while already running is a no-op; port unchanged.
        assertThat(hub.actualPort()).isEqualTo(firstPort);
    }

    @Test
    void doubleStop_isIdempotent() {
        hub.stop();
        assertThat(hub.actualPort()).isEqualTo(-1);
        hub.stop(); // second stop must not throw
        assertThat(hub.actualPort()).isEqualTo(-1);
    }

    @Test
    void ingest_skipsTokenMessageSnapshotAppend() {
        // Token messages broadcast through the hub but must NOT land in the per-producer
        // snapshot (mirrors the embedded broadcastEphemeral policy). After ingesting only
        // a token, the snapshot remains empty.
        InMemoryLiveEventPublisher p = new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        p.start();
        p.accept(serializer.toJson(
                new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL")));
        p.accept(serializer.toJson(new net.agentensemble.web.protocol.TokenMessage(
                "tok", "a", "t", Instant.parse("2026-05-21T10:00:01Z"))));
        assertThat(hub.producers().find("p1").snapshot().flattenedSnapshotMessages())
                .as("token should not be appended to the per-producer snapshot")
                .doesNotContain(serializer.toJson(new net.agentensemble.web.protocol.TokenMessage(
                        "tok", "a", "t", Instant.parse("2026-05-21T10:00:01Z"))));
        p.stop();
    }

    @Test
    void reviewRequested_routesDecisionToOriginatingProducer() throws Exception {
        InMemoryLiveEventPublisher p = new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        p.start();
        java.util.concurrent.CompletableFuture<net.agentensemble.web.protocol.ReviewDecisionForwardMessage> received =
                new java.util.concurrent.CompletableFuture<>();
        p.subscribeToReviewDecisions(received::complete);

        // Ingest a review_requested envelope.
        p.accept(serializer.toJson(
                new ReviewRequestedMessage("r1", "task", "out", "AFTER_EXECUTION", null, 0, "CONTINUE", null)));

        // Connect a browser and submit a decision.
        WebSocket browser = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + hub.actualPort() + "/ws"), new WebSocket.Listener() {
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
        browser.sendText(serializer.toJson(new ReviewDecisionMessage("r1", "continue", null)), true);

        net.agentensemble.web.protocol.ReviewDecisionForwardMessage forwarded = received.get(5, TimeUnit.SECONDS);
        assertThat(forwarded.reviewId()).isEqualTo("r1");
        assertThat(forwarded.decisionJson()).contains("\"continue\"");

        browser.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        p.stop();
    }
}
