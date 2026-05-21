package net.agentensemble.web.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.EnsembleStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.TaskStartedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a browser connecting after publishers have already emitted events receives a
 * {@code hub_hello} whose {@code snapshotTrace} contains every envelope across all retained
 * producers.
 */
class LiveEventHubLateJoinTest {

    private LiveEventHub hub;
    private WebSocket browser;
    private final MessageSerializer serializer = new MessageSerializer();

    @BeforeEach
    void setUp() {
        hub = LiveEventHub.builder().port(0).host("0.0.0.0").build();
        hub.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browser != null) {
            try {
                browser.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (hub != null) hub.stop();
    }

    @Test
    void lateJoinHello_carriesAllProducersAndAllEnvelopes() throws Exception {
        InMemoryLiveEventPublisher p1 = new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        InMemoryLiveEventPublisher p2 = new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p2", "svc"), serializer);
        InMemoryLiveEventPublisher p3 = new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p3", "svc"), serializer);
        p1.start();
        p2.start();
        p3.start();

        // Each publisher emits an ensemble_started so the per-run snapshot opens; then a task_started.
        p1.accept(serializer.toJson(
                new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL")));
        p1.accept(serializer.toJson(new TaskStartedMessage(1, 1, "T1", "A", Instant.parse("2026-05-21T10:00:01Z"))));
        p2.accept(serializer.toJson(
                new EnsembleStartedMessage("run-2", Instant.parse("2026-05-21T10:00:02Z"), 1, "SEQUENTIAL")));
        p2.accept(serializer.toJson(new TaskStartedMessage(1, 1, "T2", "B", Instant.parse("2026-05-21T10:00:03Z"))));
        p3.accept(serializer.toJson(
                new EnsembleStartedMessage("run-3", Instant.parse("2026-05-21T10:00:04Z"), 1, "SEQUENTIAL")));
        p3.accept(serializer.toJson(new TaskStartedMessage(1, 1, "T3", "C", Instant.parse("2026-05-21T10:00:05Z"))));

        // Now connect a browser and assert the hub_hello includes everything.
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        browser = connectBrowser(received, helloLatch);
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        String hello = received.stream()
                .filter(m -> m.contains("\"type\":\"hub_hello\""))
                .findFirst()
                .orElseThrow();
        assertThat(hello).contains("\"p1\"");
        assertThat(hello).contains("\"p2\"");
        assertThat(hello).contains("\"p3\"");
        assertThat(hello).contains("\"snapshotTrace\"");
        // Snapshot should contain at least one envelope for each producer.
        assertThat(hello).contains("\"producerId\":\"p1\"");
        assertThat(hello).contains("\"producerId\":\"p2\"");
        assertThat(hello).contains("\"producerId\":\"p3\"");
        // And each producer's task_started inner message should be there.
        assertThat(hello).contains("\"T1\"");
        assertThat(hello).contains("\"T2\"");
        assertThat(hello).contains("\"T3\"");

        // Cross-producer order must follow the hub-side receivedAt timestamps, so a
        // browser replaying the snapshotTrace sees envelopes in the same chronological
        // sequence the hub observed them. T1 was ingested before T2 before T3, so their
        // appearances in the snapshot JSON must be in that order regardless of the
        // ConcurrentHashMap iteration order of the producer registry.
        int idxT1 = hello.indexOf("\"T1\"");
        int idxT2 = hello.indexOf("\"T2\"");
        int idxT3 = hello.indexOf("\"T3\"");
        assertThat(idxT1).isLessThan(idxT2);
        assertThat(idxT2).isLessThan(idxT3);

        p1.stop();
        p2.stop();
        p3.stop();
    }

    private WebSocket connectBrowser(List<String> sink, CountDownLatch helloLatch) throws Exception {
        URI uri = URI.create("ws://localhost:" + hub.actualPort() + "/ws");
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String msg = buf.toString();
                            buf.setLength(0);
                            sink.add(msg);
                            if (msg.contains("\"type\":\"hub_hello\"")) helloLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
    }
}
