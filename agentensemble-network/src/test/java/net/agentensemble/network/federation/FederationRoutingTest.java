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
 * Integration tests for federation routing across multiple realms.
 *
 * <p>Setup: 3 ensembles across 2 realms, all providing the same capability.
 * <ul>
 *   <li>{@code kitchen-A} in {@code realm-downtown} (local)</li>
 *   <li>{@code kitchen-B} in {@code realm-downtown} (local)</li>
 *   <li>{@code kitchen-C} in {@code realm-airport} (remote, shareable)</li>
 * </ul>
 */
class FederationRoutingTest {

    private static final String LOCAL_REALM = "realm-downtown";
    private static final String REMOTE_REALM = "realm-airport";
    private static final String TOOL_NAME = "prepare-meal";

    private CapabilityRegistry capabilityRegistry;
    private FederationRegistry federationRegistry;

    @BeforeEach
    void setUp() {
        capabilityRegistry = new CapabilityRegistry();
        federationRegistry = new FederationRegistry(capabilityRegistry);

        // Register capabilities for all 3 ensembles
        capabilityRegistry.register(
                "kitchen-A", List.of(new SharedCapabilityInfo(TOOL_NAME, "Prepare a meal", "TASK", List.of("food"))));
        capabilityRegistry.register(
                "kitchen-B", List.of(new SharedCapabilityInfo(TOOL_NAME, "Prepare a meal", "TASK", List.of("food"))));
        capabilityRegistry.register(
                "kitchen-C", List.of(new SharedCapabilityInfo(TOOL_NAME, "Prepare a meal", "TASK", List.of("food"))));

        // Set up capacity for all 3 ensembles
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-A", LOCAL_REALM, "available", 0.6, 10, false));
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-B", LOCAL_REALM, "available", 0.3, 10, false));
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-C", REMOTE_REALM, "available", 0.1, 10, true));
    }

    // ========================
    // Routing hierarchy
    // ========================

    @Test
    void findProvider_prefersLocalRealmOverCrossRealm() {
        Optional<String> provider = federationRegistry.findProvider(TOOL_NAME, LOCAL_REALM);

        // Should prefer a local realm provider, picking the least loaded (kitchen-B at 0.3)
        assertThat(provider).isPresent();
        assertThat(provider.get()).isIn("kitchen-A", "kitchen-B");
    }

    @Test
    void findProvider_prefersLeastLoadedWithinLocalRealm() {
        Optional<String> provider = federationRegistry.findProvider(TOOL_NAME, LOCAL_REALM);

        // kitchen-B has load 0.3 vs kitchen-A's 0.6
        assertThat(provider).contains("kitchen-B");
    }

    @Test
    void findProvider_fallsToCrossRealm_whenNoLocalProviders() {
        // Request from a realm with no providers
        Optional<String> provider = federationRegistry.findProvider(TOOL_NAME, "realm-beach");

        // kitchen-C is shareable and the only option for cross-realm
        assertThat(provider).contains("kitchen-C");
    }

    @Test
    void findProvider_doesNotUseCrossRealm_whenNotShareable() {
        // Make kitchen-C not shareable
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-C", REMOTE_REALM, "available", 0.1, 10, false));

        // Request from a realm with no providers
        Optional<String> provider = federationRegistry.findProvider(TOOL_NAME, "realm-beach");

        assertThat(provider).isEmpty();
    }

    // ========================
    // Load-based selection
    // ========================

    @Test
    void findLeastLoadedProvider_selectsLowestLoadAcrossAllEligible() {
        // From local realm perspective, kitchen-A, kitchen-B are local; kitchen-C is shareable
        Optional<String> provider = federationRegistry.findLeastLoadedProvider(TOOL_NAME, LOCAL_REALM);

        // kitchen-C has lowest load (0.1) and is shareable
        assertThat(provider).contains("kitchen-C");
    }

    @Test
    void findLeastLoadedProvider_ignoresNonShareableCrossRealm() {
        // Make kitchen-C not shareable
        federationRegistry.updateCapacity(
                new CapacityUpdateMessage("kitchen-C", REMOTE_REALM, "available", 0.1, 10, false));

        Optional<String> provider = federationRegistry.findLeastLoadedProvider(TOOL_NAME, LOCAL_REALM);

        // kitchen-B has lowest load among eligible (local realm) providers
        assertThat(provider).contains("kitchen-B");
    }

    @Test
    void loadBasedSelection_updatesWithCapacityChanges() {
        // Shift load: kitchen-B becomes overloaded
        federationRegistry.updateCapacity(new CapacityUpdateMessage("kitchen-B", LOCAL_REALM, "busy", 0.95, 10, false));

        Optional<String> provider = federationRegistry.findProvider(TOOL_NAME, LOCAL_REALM);

        // Now kitchen-A (0.6) should be preferred over kitchen-B (0.95) in local realm
        assertThat(provider).contains("kitchen-A");
    }
}
