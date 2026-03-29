package net.agentensemble.network.federation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import net.agentensemble.network.CapabilityRegistry;
import net.agentensemble.web.protocol.CapacityUpdateMessage;
import net.agentensemble.web.protocol.SharedCapabilityInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FederationRegistry}.
 */
class FederationRegistryTest {

    private CapabilityRegistry capabilityRegistry;
    private FederationRegistry federationRegistry;

    @BeforeEach
    void setUp() {
        capabilityRegistry = new CapabilityRegistry();
        federationRegistry = new FederationRegistry(capabilityRegistry);
    }

    // ========================
    // Empty registry
    // ========================

    @Test
    void emptyRegistry_findProviderReturnsEmpty() {
        assertThat(federationRegistry.findProvider("non-existent", "my-realm")).isEmpty();
    }

    @Test
    void emptyRegistry_findLeastLoadedProviderReturnsEmpty() {
        assertThat(federationRegistry.findLeastLoadedProvider("non-existent", "my-realm"))
                .isEmpty();
    }

    @Test
    void emptyRegistry_getCapacityReturnsEmpty() {
        assertThat(federationRegistry.getCapacity("non-existent")).isEmpty();
    }

    @Test
    void emptyRegistry_getRealmReturnsEmpty() {
        assertThat(federationRegistry.getRealm("non-existent")).isEmpty();
    }

    // ========================
    // updateCapacity
    // ========================

    @Test
    void updateCapacity_storesStatus() {
        CapacityUpdateMessage update =
                new CapacityUpdateMessage("kitchen", "hotel-downtown", "available", 0.3, 10, true);

        federationRegistry.updateCapacity(update);

        Optional<CapacityStatus> status = federationRegistry.getCapacity("kitchen");
        assertThat(status).isPresent();
        assertThat(status.get().ensemble()).isEqualTo("kitchen");
        assertThat(status.get().realm()).isEqualTo("hotel-downtown");
        assertThat(status.get().status()).isEqualTo("available");
        assertThat(status.get().currentLoad()).isEqualTo(0.3);
        assertThat(status.get().maxConcurrent()).isEqualTo(10);
        assertThat(status.get().shareable()).isTrue();
    }

    @Test
    void updateCapacity_storesRealm() {
        CapacityUpdateMessage update =
                new CapacityUpdateMessage("kitchen", "hotel-downtown", "available", 0.3, 10, true);

        federationRegistry.updateCapacity(update);

        assertThat(federationRegistry.getRealm("kitchen")).contains("hotel-downtown");
    }

    // ========================
    // findProvider -- routing hierarchy
    // ========================

    @Test
    void findProvider_prefersLocalRealm() {
        // Register capabilities for two ensembles providing the same tool
        capabilityRegistry.register(
                "kitchen-local", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));
        capabilityRegistry.register(
                "kitchen-remote", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        // kitchen-local is in our realm, kitchen-remote is in a different realm
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-local", "hotel-downtown", "available", 0.5, 10, false));
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-remote", "hotel-airport", "available", 0.1, 10, true));

        Optional<String> provider = federationRegistry.findProvider("prepare-meal", "hotel-downtown");

        assertThat(provider).contains("kitchen-local");
    }

    @Test
    void findProvider_fallsBackToSameRealm_noRealmInfo() {
        // Register an ensemble without realm info (no capacity update sent)
        capabilityRegistry.register(
                "kitchen-unknown", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        // No capacity updates for kitchen-unknown -- it has no realm info
        Optional<String> provider = federationRegistry.findProvider("prepare-meal", "hotel-downtown");

        // Should fall back to "no realm info = assume local"
        assertThat(provider).contains("kitchen-unknown");
    }

    @Test
    void findProvider_fallsBackToCrossRealm_onlyIfShareable() {
        capabilityRegistry.register(
                "kitchen-remote", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        // Remote ensemble is shareable
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-remote", "hotel-airport", "available", 0.2, 10, true));

        Optional<String> provider = federationRegistry.findProvider("prepare-meal", "hotel-downtown");

        assertThat(provider).contains("kitchen-remote");
    }

    @Test
    void findProvider_returnsEmpty_whenCrossRealmNotShareable() {
        capabilityRegistry.register(
                "kitchen-remote", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        // Remote ensemble is NOT shareable
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-remote", "hotel-airport", "available", 0.2, 10, false));

        Optional<String> provider = federationRegistry.findProvider("prepare-meal", "hotel-downtown");

        assertThat(provider).isEmpty();
    }

    // ========================
    // findLeastLoadedProvider
    // ========================

    @Test
    void findLeastLoadedProvider_selectsLowestLoad() {
        capabilityRegistry.register(
                "kitchen-a", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));
        capabilityRegistry.register(
                "kitchen-b", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-a", "hotel-downtown", "available", 0.8, 10, false));
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-b", "hotel-downtown", "available", 0.2, 10, false));

        Optional<String> provider = federationRegistry.findLeastLoadedProvider("prepare-meal", "hotel-downtown");

        assertThat(provider).contains("kitchen-b");
    }

    @Test
    void findLeastLoadedProvider_respectsShareability() {
        capabilityRegistry.register(
                "kitchen-local", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));
        capabilityRegistry.register(
                "kitchen-remote", List.of(new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));

        // Local is busy, remote has low load but is NOT shareable
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-local", "hotel-downtown", "available", 0.9, 10, false));
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-remote", "hotel-airport", "available", 0.1, 10, false));

        Optional<String> provider = federationRegistry.findLeastLoadedProvider("prepare-meal", "hotel-downtown");

        // Should pick local because remote is not shareable and in a different realm
        assertThat(provider).contains("kitchen-local");
    }

    // ========================
    // getCapacity / getRealm
    // ========================

    @Test
    void getCapacity_returnsStoredStatus() {
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen", "hotel-downtown", "busy", 0.95, 10, true));

        Optional<CapacityStatus> status = federationRegistry.getCapacity("kitchen");

        assertThat(status).isPresent();
        assertThat(status.get().status()).isEqualTo("busy");
        assertThat(status.get().currentLoad()).isEqualTo(0.95);
    }

    @Test
    void getRealm_returnsRealm() {
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen", "hotel-downtown", "available", 0.3, 10, true));

        assertThat(federationRegistry.getRealm("kitchen")).contains("hotel-downtown");
    }
}
