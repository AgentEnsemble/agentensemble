package net.agentensemble.network.transport.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpIngress}.
 */
class HttpIngressTest {

    private HttpIngress ingress;

    @AfterEach
    void tearDown() {
        if (ingress != null) {
            ingress.stop();
        }
    }

    // ========================
    // name
    // ========================

    @Test
    void name_returnsHttpPrefixed() {
        ingress = new HttpIngress(8080);
        assertThat(ingress.name()).isEqualTo("http:8080");
    }

    // ========================
    // start / POST
    // ========================

    @Test
    void start_acceptsPostRequests() throws Exception {
        ingress = new HttpIngress(0);

        CountDownLatch latch = new CountDownLatch(1);
        List<WorkRequest> received = Collections.synchronizedList(new ArrayList<>());

        ingress.start(req -> {
            received.add(req);
            latch.countDown();
        });

        int port = ingress.boundPort();

        String json =
                """
                {
                    "requestId": "req-42",
                    "from": "test-ensemble",
                    "task": "do-something",
                    "context": "hello world"
                }
                """;

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/work"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("req-42");
        assertThat(response.body()).contains("ACCEPTED");

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).requestId()).isEqualTo("req-42");
        assertThat(received.get(0).task()).isEqualTo("do-something");
        assertThat(received.get(0).context()).isEqualTo("hello world");
    }

    @Test
    void start_invalidJson_returns400() throws Exception {
        ingress = new HttpIngress(0);
        ingress.start(req -> {});

        int port = ingress.boundPort();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/work"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("not valid json {{{"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("Bad Request");
    }

    @Test
    void start_nullSink_throwsNPE() {
        ingress = new HttpIngress(0);
        assertThatThrownBy(() -> ingress.start(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // stop
    // ========================

    @Test
    void stop_stopsServer() throws Exception {
        ingress = new HttpIngress(0);
        ingress.start(req -> {});

        int port = ingress.boundPort();
        ingress.stop();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/work"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.io.IOException.class);
    }

    // ========================
    // boundPort
    // ========================

    @Test
    void boundPort_beforeStart_throwsISE() {
        ingress = new HttpIngress(0);
        assertThatThrownBy(() -> ingress.boundPort()).isInstanceOf(IllegalStateException.class);
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_nullHost_throwsNPE() {
        assertThatThrownBy(() -> new HttpIngress(0, null)).isInstanceOf(NullPointerException.class);
    }
}
