package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.directive.Directive;
import net.agentensemble.directive.DirectiveStore;
import net.agentensemble.review.OnTimeoutAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for directive handling in {@link WebDashboard}.
 *
 * <p>Connects via real WebSocket and exercises the handleDirective path.
 */
class WebDashboardDirectiveTest {

    private WebDashboard dashboard;
    private DirectiveStore store;
    private WebSocket ws;

    @BeforeEach
    void setUp() {
        store = new DirectiveStore();
        dashboard = WebDashboard.builder()
                .port(0)
                .host("localhost")
                .reviewTimeout(Duration.ofMillis(100))
                .onTimeout(OnTimeoutAction.CONTINUE)
                .build();
        dashboard.setDirectiveStore(store);
        dashboard.start();
    }

    @AfterEach
    void tearDown() {
        try {
            if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        if (dashboard != null) dashboard.stop();
    }

    private WebSocket connectAndWaitForHello() throws Exception {
        CountDownLatch helloLatch = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket socket = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(10);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last && data.toString().contains("\"type\":\"hello\"")) {
                            helloLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();
        return socket;
    }

    @Test
    void contextDirective_isStoredAndBroadcast() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch ackLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("directive_ack")) {
                                ackLatch.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        // Wait for hello
        Thread.sleep(200);

        // Send a context directive
        String directiveJson =
                "{\"type\":\"directive\",\"to\":\"kitchen\",\"from\":\"manager:human\",\"content\":\"VIP in 801\"}";
        ws.sendText(directiveJson, true).get(2, TimeUnit.SECONDS);

        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify directive stored
        List<Directive> active = store.activeContextDirectives();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).content()).isEqualTo("VIP in 801");

        // Verify directive_active was broadcast
        boolean hasActive = received.stream().anyMatch(m -> m.contains("directive_active"));
        assertThat(hasActive).isTrue();

        // Verify directive_ack was sent
        boolean hasAck = received.stream().anyMatch(m -> m.contains("directive_ack"));
        assertThat(hasAck).isTrue();
    }

    @Test
    void contextDirective_blankContent_rejected() throws Exception {
        CountDownLatch ackLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("REJECTED")) {
                                ackLatch.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        String directiveJson = "{\"type\":\"directive\",\"to\":\"kitchen\",\"from\":\"manager\",\"content\":\"\"}";
        ws.sendText(directiveJson, true).get(2, TimeUnit.SECONDS);

        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.activeContextDirectives()).isEmpty();
        assertThat(received.stream().anyMatch(m -> m.contains("REJECTED"))).isTrue();
    }

    @Test
    void controlPlaneDirective_isStored() throws Exception {
        CountDownLatch ackLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last && data.toString().contains("directive_ack")) {
                            ackLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        String directiveJson = "{\"type\":\"directive\",\"to\":\"kitchen\",\"from\":\"cost-policy:auto\","
                + "\"action\":\"SET_MODEL_TIER\",\"value\":\"FALLBACK\"}";
        ws.sendText(directiveJson, true).get(2, TimeUnit.SECONDS);

        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        List<Directive> active = store.activeControlPlaneDirectives();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).action()).isEqualTo("SET_MODEL_TIER");
    }

    @Test
    void directive_withTtl_setsExpiry() throws Exception {
        CountDownLatch ackLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last && data.toString().contains("directive_ack")) {
                            ackLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        String directiveJson = "{\"type\":\"directive\",\"to\":\"kitchen\",\"from\":\"manager\","
                + "\"content\":\"Use premium ingredients\",\"ttl\":\"PT1H\"}";
        ws.sendText(directiveJson, true).get(2, TimeUnit.SECONDS);

        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        List<Directive> active = store.activeContextDirectives();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).expiresAt()).isNotNull();
    }

    @Test
    void directive_negativeTtl_rejected() throws Exception {
        CountDownLatch ackLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last && data.toString().contains("REJECTED")) {
                            ackLatch.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(200);

        String directiveJson = "{\"type\":\"directive\",\"to\":\"kitchen\",\"from\":\"manager\","
                + "\"content\":\"Should fail\",\"ttl\":\"-PT5M\"}";
        ws.sendText(directiveJson, true).get(2, TimeUnit.SECONDS);

        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(store.activeContextDirectives()).isEmpty();
    }
}
