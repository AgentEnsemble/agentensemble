package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import net.agentensemble.web.protocol.TaskRequestMessage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkClient}.
 */
class NetworkClientTest {

    private static final String ENSEMBLE = "test-ensemble";
    private static final String WS_URL = "ws://localhost:7329/ws";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_nullEnsembleName_throwsNPE() {
        assertThatThrownBy(() -> new NetworkClient(null, WS_URL, TIMEOUT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensembleName");
    }

    @Test
    void constructor_nullWsUrl_throwsNPE() {
        assertThatThrownBy(() -> new NetworkClient(ENSEMBLE, null, TIMEOUT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("wsUrl");
    }

    @Test
    void constructor_nullConnectTimeout_throwsNPE() {
        assertThatThrownBy(() -> new NetworkClient(ENSEMBLE, WS_URL, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("connectTimeout");
    }

    // ========================
    // Accessor methods
    // ========================

    @Test
    void ensembleName_returnsConfiguredName() {
        try (NetworkClient client = new NetworkClient(ENSEMBLE, WS_URL, TIMEOUT)) {
            assertThat(client.ensembleName()).isEqualTo(ENSEMBLE);
        }
    }

    @Test
    void wsUrl_returnsConfiguredUrl() {
        try (NetworkClient client = new NetworkClient(ENSEMBLE, WS_URL, TIMEOUT)) {
            assertThat(client.wsUrl()).isEqualTo(WS_URL);
        }
    }

    @Test
    void pendingCount_startsAtZero() {
        try (NetworkClient client = new NetworkClient(ENSEMBLE, WS_URL, TIMEOUT)) {
            assertThat(client.pendingCount()).isZero();
        }
    }

    // ========================
    // Lifecycle
    // ========================

    @Test
    void close_onNeverConnectedClient_doesNotThrow() {
        NetworkClient client = new NetworkClient(ENSEMBLE, WS_URL, TIMEOUT);
        assertThatNoException().isThrownBy(client::close);
    }

    // ========================
    // Send to unreachable server
    // ========================

    @Test
    void send_toUnreachableServer_completesExceptionally() {
        // Use port 1 which is almost certainly not running a WebSocket server.
        try (NetworkClient client = new NetworkClient(ENSEMBLE, "ws://localhost:1/ws", Duration.ofSeconds(2))) {
            var msg =
                    new TaskRequestMessage("req-1", "caller", "myTask", null, null, null, null, null, null, null, null);
            assertThatThrownBy(() -> client.send(msg, "req-1"))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining(ENSEMBLE);

            // After a failed send, pending count should be zero (request cleaned up).
            assertThat(client.pendingCount()).isZero();
        }
    }
}
