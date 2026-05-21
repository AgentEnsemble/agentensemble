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
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the hub-side per-producer LLM iteration ring buffer: when a publisher emits
 * {@code llm_iteration_started} + {@code llm_iteration_completed}, the hub records the pair so
 * the next {@code hub_hello} carries the producer's recent iteration snapshots for browser
 * conversation-panel hydration on late-join.
 */
class HubIterationHydrationTest {

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
    void iterationPairs_areRecordedAndDeliveredInHubHello() throws Exception {
        InMemoryLiveEventPublisher publisher =
                new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        publisher.start();

        publisher.accept(serializer.toJson(
                new EnsembleStartedMessage("run-1", Instant.parse("2026-05-21T10:00:00Z"), 1, "SEQUENTIAL")));
        publisher.accept(serializer.toJson(new LlmIterationStartedMessage("Agent", "Task", 1, List.of(), 0)));
        publisher.accept(serializer.toJson(
                new LlmIterationCompletedMessage("Agent", "Task", 1, "FINAL", "response", List.of(), 10L, 20L, 100L)));

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        browser = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + hub.actualPort() + "/ws"), new WebSocket.Listener() {
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
                            received.add(msg);
                            if (msg.startsWith("{\"type\":\"hub_hello\"")) helloLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        String hello = received.stream()
                .filter(m -> m.startsWith("{\"type\":\"hub_hello\""))
                .findFirst()
                .orElseThrow();
        assertThat(hello).contains("\"iterationsByProducer\"");
        assertThat(hello).contains("\"p1\"");
        assertThat(hello).contains("\"response\"");

        publisher.stop();
    }
}
