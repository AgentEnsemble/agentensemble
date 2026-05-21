package net.agentensemble.web.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
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
 * End-to-end tests for {@link LiveEventHub} envelope ingestion with multiple in-memory
 * publishers feeding a single hub instance. Verifies that:
 * <ul>
 *   <li>Each producer's events are recorded into its own per-producer snapshot.</li>
 *   <li>Browser sessions receive a {@link net.agentensemble.web.protocol.HubHelloMessage} with
 *       all retained envelopes flattened in chronological order.</li>
 *   <li>Browser sessions receive enveloped events as they arrive from publishers.</li>
 * </ul>
 */
class LiveEventHubIngestionTest {

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
        if (hub != null) {
            hub.stop();
        }
    }

    @Test
    void twoPublishers_eventsAreRecordedAndBroadcast() throws Exception {
        InMemoryLiveEventPublisher pubA =
                new InMemoryLiveEventPublisher(hub, ProducerInfo.of("svc-a-1", "svc-a"), serializer);
        InMemoryLiveEventPublisher pubB =
                new InMemoryLiveEventPublisher(hub, ProducerInfo.of("svc-b-1", "svc-b"), serializer);
        pubA.start();
        pubB.start();

        // Each publisher emits an ensemble_started + a task_started.
        pubA.accept(serializer.toJson(
                new EnsembleStartedMessage("run-a", java.time.Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL")));
        pubA.accept(serializer.toJson(
                new TaskStartedMessage(1, 1, "Task A", "Agent A", java.time.Instant.parse("2026-05-21T10:00:01Z"))));
        pubB.accept(serializer.toJson(
                new EnsembleStartedMessage("run-b", java.time.Instant.parse("2026-05-21T10:00:02Z"), 1, "SEQUENTIAL")));
        pubB.accept(serializer.toJson(
                new TaskStartedMessage(1, 1, "Task B", "Agent B", java.time.Instant.parse("2026-05-21T10:00:03Z"))));

        // Both producers are known to the registry.
        List<ProducerInfo> producers = hub.producers().listProducers();
        assertThat(producers).hasSize(2);
        assertThat(producers).extracting(ProducerInfo::producerId).containsExactlyInAnyOrder("svc-a-1", "svc-b-1");

        // Each per-producer snapshot has stored its own messages.
        assertThat(hub.producers().find("svc-a-1").snapshot().flattenedSnapshotMessages())
                .hasSize(2);
        assertThat(hub.producers().find("svc-b-1").snapshot().flattenedSnapshotMessages())
                .hasSize(2);

        // Connect a browser and verify it receives a hub_hello plus the live envelopes.
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch eventLatch = new CountDownLatch(1);
        browser = connectBrowser(received, helloLatch, eventLatch);
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anyMatch(m -> m.contains("\"type\":\"hub_hello\""));

        // Emit a new envelope and observe it arrive on the browser as an "event".
        pubA.accept(serializer.toJson(
                new TaskStartedMessage(1, 1, "Task A-2", "Agent A", java.time.Instant.parse("2026-05-21T10:00:04Z"))));
        assertThat(eventLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anyMatch(m -> m.contains("\"type\":\"event\"") && m.contains("Task A-2"));

        pubA.stop();
        pubB.stop();
    }

    private WebSocket connectBrowser(List<String> sink, CountDownLatch helloLatch, CountDownLatch eventLatch)
            throws Exception {
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
                            if (msg.startsWith("{\"type\":\"hub_hello\"")) helloLatch.countDown();
                            if (msg.startsWith("{\"type\":\"event\"") && msg.contains("Task A-2"))
                                eventLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
    }
}
