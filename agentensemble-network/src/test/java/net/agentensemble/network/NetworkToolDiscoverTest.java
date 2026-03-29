package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.agentensemble.web.protocol.SharedCapabilityInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTool#discover(String, NetworkClientRegistry)}.
 */
class NetworkToolDiscoverTest {

    private NetworkConfig config;
    private NetworkClientRegistry clientRegistry;

    @BeforeEach
    void setUp() {
        config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .ensemble("maintenance", "ws://maintenance:7329/ws")
                .build();
        clientRegistry = new NetworkClientRegistry(config);
    }

    @AfterEach
    void tearDown() {
        clientRegistry.close();
    }

    @Test
    void discover_findsCorrectProvider() {
        clientRegistry
                .getCapabilityRegistry()
                .register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));

        NetworkTool tool = NetworkTool.discover("check-inventory", clientRegistry);

        assertThat(tool.ensembleName()).isEqualTo("kitchen");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
        assertThat(tool.executionTimeout()).isEqualTo(NetworkTool.DEFAULT_EXECUTION_TIMEOUT);
        assertThat(tool.name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void discover_throwsWhenNoProvider() {
        assertThatThrownBy(() -> NetworkTool.discover("non-existent", clientRegistry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No provider found")
                .hasMessageContaining("non-existent");
    }

    @Test
    void discover_nullToolNameThrows() {
        assertThatThrownBy(() -> NetworkTool.discover(null, clientRegistry))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toolName");
    }

    @Test
    void discover_nullClientRegistryThrows() {
        assertThatThrownBy(() -> NetworkTool.discover("check-inventory", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRegistry");
    }

    @Test
    void discover_findsFirstProviderWhenMultipleExist() {
        clientRegistry
                .getCapabilityRegistry()
                .register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "maintenance",
                        List.of(new SharedCapabilityInfo("check-inventory", "Maintenance stock", "TOOL")));

        NetworkTool tool = NetworkTool.discover("check-inventory", clientRegistry);

        // Should find one of the two providers
        assertThat(tool.ensembleName()).isIn("kitchen", "maintenance");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
    }
}
