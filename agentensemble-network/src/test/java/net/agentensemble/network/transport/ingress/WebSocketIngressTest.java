package net.agentensemble.network.transport.ingress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketIngress}.
 */
class WebSocketIngressTest {

    // ========================
    // name
    // ========================

    @Test
    void name_returnsWebSocketPrefixed() {
        WebSocketIngress ingress = new WebSocketIngress("dashboard");
        assertThat(ingress.name()).isEqualTo("websocket:dashboard");
    }

    // ========================
    // onWorkRequest
    // ========================

    @Test
    void onWorkRequest_beforeStart_doesNothing() {
        WebSocketIngress ingress = new WebSocketIngress("test");
        // Should not throw, request is silently dropped
        assertThatNoException().isThrownBy(() -> ingress.onWorkRequest(workRequest("req-1", "task")));
    }

    @Test
    void onWorkRequest_afterStart_pushesToSink() {
        WebSocketIngress ingress = new WebSocketIngress("test");
        List<WorkRequest> received = new ArrayList<>();

        ingress.start(received::add);
        ingress.onWorkRequest(workRequest("req-1", "task"));

        assertThat(received).hasSize(1);
        assertThat(received.get(0).requestId()).isEqualTo("req-1");
    }

    @Test
    void onWorkRequest_afterStop_doesNothing() {
        WebSocketIngress ingress = new WebSocketIngress("test");
        List<WorkRequest> received = new ArrayList<>();

        ingress.start(received::add);
        ingress.stop();
        ingress.onWorkRequest(workRequest("req-1", "task"));

        assertThat(received).isEmpty();
    }

    @Test
    void onWorkRequest_multipleRequests() {
        WebSocketIngress ingress = new WebSocketIngress("test");
        List<WorkRequest> received = new ArrayList<>();

        ingress.start(received::add);
        ingress.onWorkRequest(workRequest("req-1", "task-a"));
        ingress.onWorkRequest(workRequest("req-2", "task-b"));
        ingress.onWorkRequest(workRequest("req-3", "task-c"));

        assertThat(received).hasSize(3);
        assertThat(received).extracting(WorkRequest::requestId).containsExactly("req-1", "req-2", "req-3");
    }

    // ========================
    // Null validation
    // ========================

    @Test
    void constructor_nullName_throwsNPE() {
        assertThatThrownBy(() -> new WebSocketIngress(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void start_nullSink_throwsNPE() {
        WebSocketIngress ingress = new WebSocketIngress("test");
        assertThatThrownBy(() -> ingress.start(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void onWorkRequest_nullRequest_throwsNPE() {
        WebSocketIngress ingress = new WebSocketIngress("test");
        ingress.start(r -> {});
        assertThatThrownBy(() -> ingress.onWorkRequest(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Helpers
    // ========================

    private static WorkRequest workRequest(String requestId, String task) {
        return new WorkRequest(requestId, "test-ensemble", task, null, null, null, null, null, null, null, null);
    }
}
