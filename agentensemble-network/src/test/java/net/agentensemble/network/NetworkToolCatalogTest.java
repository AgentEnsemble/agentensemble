package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.web.protocol.SharedCapabilityInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkToolCatalog}.
 */
class NetworkToolCatalogTest {

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

    // ========================
    // all()
    // ========================

    @Test
    void all_resolvesToolsFromRegistry() {
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "kitchen",
                        List.of(
                                new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL"),
                                new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        // Only TOOL type should be returned, not TASK
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void all_resolvesToolsFromMultipleEnsembles() {
        clientRegistry
                .getCapabilityRegistry()
                .register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));
        clientRegistry
                .getCapabilityRegistry()
                .register("maintenance", List.of(new SharedCapabilityInfo("fix-pipe", "Fix plumbing", "TOOL")));

        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        assertThat(tools).hasSize(2);
        assertThat(tools)
                .extracting(AgentTool::name)
                .containsExactlyInAnyOrder("kitchen.check-inventory", "maintenance.fix-pipe");
    }

    @Test
    void all_emptyRegistryReturnsEmpty() {
        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        assertThat(tools).isEmpty();
    }

    @Test
    void all_onlyToolTypeReturned() {
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "kitchen",
                        List.of(
                                new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL"),
                                new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK"),
                                new SharedCapabilityInfo("dietary-check", "Check allergens", "TOOL")));

        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        assertThat(tools).hasSize(2);
        assertThat(tools)
                .extracting(AgentTool::name)
                .containsExactlyInAnyOrder("kitchen.check-inventory", "kitchen.dietary-check");
    }

    @Test
    void all_tagFilterIsNull() {
        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);
        assertThat(catalog.tagFilter()).isNull();
    }

    // ========================
    // tagged()
    // ========================

    @Test
    void tagged_filtersCorrectly() {
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "kitchen",
                        List.of(
                                new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food")),
                                new SharedCapabilityInfo("clean-kitchen", "Clean up", "TOOL", List.of("cleaning"))));
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "maintenance",
                        List.of(new SharedCapabilityInfo("fix-pipe", "Fix plumbing", "TOOL", List.of("plumbing"))));

        NetworkToolCatalog catalog = NetworkToolCatalog.tagged("food", clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void tagged_filtersOutTaskType() {
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "kitchen",
                        List.of(
                                new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food")),
                                new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK", List.of("food"))));

        NetworkToolCatalog catalog = NetworkToolCatalog.tagged("food", clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void tagged_returnsTagFilter() {
        NetworkToolCatalog catalog = NetworkToolCatalog.tagged("food", clientRegistry);
        assertThat(catalog.tagFilter()).isEqualTo("food");
    }

    @Test
    void tagged_nullTagThrows() {
        assertThatThrownBy(() -> NetworkToolCatalog.tagged(null, clientRegistry))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tag");
    }

    @Test
    void tagged_unknownTagReturnsEmpty() {
        clientRegistry
                .getCapabilityRegistry()
                .register(
                        "kitchen",
                        List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food"))));

        NetworkToolCatalog catalog = NetworkToolCatalog.tagged("non-existent", clientRegistry);
        List<AgentTool> tools = catalog.resolve();

        assertThat(tools).isEmpty();
    }

    // ========================
    // Dynamic resolution
    // ========================

    @Test
    void resolve_picksUpNewlyRegisteredCapabilities() {
        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);

        // Initially empty
        assertThat(catalog.resolve()).isEmpty();

        // Register capabilities after catalog creation
        clientRegistry
                .getCapabilityRegistry()
                .register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));

        // Resolve again -- should pick up the new tool
        List<AgentTool> tools = catalog.resolve();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void resolve_reflectsUnregisteredCapabilities() {
        clientRegistry
                .getCapabilityRegistry()
                .register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));

        NetworkToolCatalog catalog = NetworkToolCatalog.all(clientRegistry);
        assertThat(catalog.resolve()).hasSize(1);

        // Unregister
        clientRegistry.getCapabilityRegistry().unregister("kitchen");

        assertThat(catalog.resolve()).isEmpty();
    }

    // ========================
    // Factory validation
    // ========================

    @Test
    void all_nullRegistryThrows() {
        assertThatThrownBy(() -> NetworkToolCatalog.all(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRegistry");
    }

    @Test
    void tagged_nullRegistryThrows() {
        assertThatThrownBy(() -> NetworkToolCatalog.tagged("food", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRegistry");
    }
}
