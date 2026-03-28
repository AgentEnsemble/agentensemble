package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkClientRegistry}.
 */
class NetworkClientRegistryTest {

    private NetworkConfig config;
    private NetworkClientRegistry registry;

    @BeforeEach
    void setUp() {
        config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .ensemble("maintenance", "ws://maintenance:7329/ws")
                .build();
        registry = new NetworkClientRegistry(config);
    }

    @AfterEach
    void tearDown() {
        registry.close();
    }

    @Test
    void getOrCreate_returnsClientForKnownEnsemble() {
        NetworkClient client = registry.getOrCreate("kitchen");

        assertThat(client).isNotNull();
        assertThat(client.ensembleName()).isEqualTo("kitchen");
        assertThat(client.wsUrl()).isEqualTo("ws://kitchen:7329/ws");
    }

    @Test
    void getOrCreate_throwsForUnknownEnsemble() {
        assertThatThrownBy(() -> registry.getOrCreate("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .hasMessageContaining("No WebSocket URL configured");
    }

    @Test
    void getOrCreate_returnsSameClientForSameName() {
        NetworkClient first = registry.getOrCreate("kitchen");
        NetworkClient second = registry.getOrCreate("kitchen");

        assertThat(first).isSameAs(second);
    }

    @Test
    void getOrCreate_returnsDifferentClientsForDifferentNames() {
        NetworkClient kitchen = registry.getOrCreate("kitchen");
        NetworkClient maintenance = registry.getOrCreate("maintenance");

        assertThat(kitchen).isNotSameAs(maintenance);
        assertThat(kitchen.ensembleName()).isEqualTo("kitchen");
        assertThat(maintenance.ensembleName()).isEqualTo("maintenance");
    }

    @Test
    void size_tracksNumberOfClients() {
        assertThat(registry.size()).isZero();

        registry.getOrCreate("kitchen");
        assertThat(registry.size()).isEqualTo(1);

        registry.getOrCreate("maintenance");
        assertThat(registry.size()).isEqualTo(2);

        // Same name should not increase size
        registry.getOrCreate("kitchen");
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void close_clearsRegistry() {
        registry.getOrCreate("kitchen");
        registry.getOrCreate("maintenance");
        assertThat(registry.size()).isEqualTo(2);

        registry.close();

        assertThat(registry.size()).isZero();
    }
}
