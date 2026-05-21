package net.agentensemble.web.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
 * Unit tests for {@link HttpLiveEventPublisher}. The publisher only POSTs envelopes and reports
 * lifecycle / capability; tests exercise those paths against a small in-process HttpServer so
 * we cover the actual network code path without standing up a full hub.
 */
class HttpLiveEventPublisherTest {

    private HttpServer server;
    private HttpLiveEventPublisher publisher;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private CountDownLatch ingestLatch;

    @BeforeEach
    void setUp() throws Exception {
        ingestLatch = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/hub/ingress", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new String(body, StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            ingestLatch.countDown();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (publisher != null) publisher.stop();
        if (server != null) server.stop(0);
    }

    @Test
    void lifecycleFlags_followStartStop() {
        publisher = new HttpLiveEventPublisher(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/hub/ingress"),
                ProducerInfo.of("p1", "svc"),
                new MessageSerializer());
        assertThat(publisher.isConnected()).isFalse();
        publisher.start();
        assertThat(publisher.isConnected()).isTrue();
        publisher.stop();
        assertThat(publisher.isConnected()).isFalse();
        // Second stop is a no-op (idempotent).
        publisher.stop();
        assertThat(publisher.isConnected()).isFalse();
    }

    @Test
    void supportsReviewFanIn_isFalseAndSubscribeRejected() {
        publisher = new HttpLiveEventPublisher(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/hub/ingress"),
                ProducerInfo.of("p1", "svc"),
                new MessageSerializer());
        assertThat(publisher.supportsReviewFanIn()).isFalse();
        assertThatThrownBy(() -> publisher.subscribeToReviewDecisions(d -> {}))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("review fan-in");
    }

    @Test
    void publish_postsEnvelopeBodyToIngressEndpoint() throws Exception {
        MessageSerializer serializer = new MessageSerializer();
        publisher = new HttpLiveEventPublisher(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/hub/ingress"),
                ProducerInfo.of("p1", "svc"),
                serializer);
        publisher.start();

        publisher.accept(serializer.toJson(new TaskStartedMessage(1, 1, "T", "A", Instant.now())));

        assertThat(ingestLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("\"type\":\"event\"");
        assertThat(received.get(0)).contains("\"producerId\":\"p1\"");
        assertThat(received.get(0)).contains("\"task_started\"");
    }
}
