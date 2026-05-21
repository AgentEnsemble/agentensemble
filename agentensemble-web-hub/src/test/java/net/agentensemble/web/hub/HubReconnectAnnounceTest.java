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
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a producer which leaves and re-joins triggers a fresh {@code producer_joined}
 * broadcast. Regression test for the bug where {@code announcedProducers} was sticky and
 * subsequent re-joins were silently swallowed.
 */
class HubReconnectAnnounceTest {

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
    void reJoiningProducer_broadcastsProducerJoinedAgain() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstJoin = new CountDownLatch(1);
        CountDownLatch left = new CountDownLatch(1);
        CountDownLatch secondJoin = new CountDownLatch(1);
        browser = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + hub.actualPort() + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();
                    private boolean joinedOnce = false;

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
                            if (msg.startsWith("{\"type\":\"producer_joined\"")) {
                                if (!joinedOnce) {
                                    joinedOnce = true;
                                    firstJoin.countDown();
                                } else {
                                    secondJoin.countDown();
                                }
                            } else if (msg.startsWith("{\"type\":\"producer_left\"")) {
                                left.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        InMemoryLiveEventPublisher publisher =
                new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        publisher.start();
        assertThat(firstJoin.await(5, TimeUnit.SECONDS))
                .as("first start should broadcast producer_joined")
                .isTrue();

        publisher.stop();
        assertThat(left.await(5, TimeUnit.SECONDS))
                .as("stop should broadcast producer_left")
                .isTrue();

        publisher.start();
        assertThat(secondJoin.await(5, TimeUnit.SECONDS))
                .as("re-start should broadcast producer_joined a second time")
                .isTrue();

        publisher.stop();
    }
}
