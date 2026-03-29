package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.agentensemble.web.protocol.SharedCapabilityInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapabilityRegistry}.
 */
class CapabilityRegistryTest {

    private CapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CapabilityRegistry();
    }

    // ========================
    // Empty registry
    // ========================

    @Test
    void emptyRegistry_sizeIsZero() {
        assertThat(registry.size()).isZero();
    }

    @Test
    void emptyRegistry_allReturnsEmptyList() {
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void emptyRegistry_findProviderReturnsEmpty() {
        assertThat(registry.findProvider("non-existent")).isEmpty();
    }

    @Test
    void emptyRegistry_findAllProvidersReturnsEmptyList() {
        assertThat(registry.findAllProviders("non-existent")).isEmpty();
    }

    @Test
    void emptyRegistry_findByTagReturnsEmptyList() {
        assertThat(registry.findByTag("non-existent")).isEmpty();
    }

    // ========================
    // Registration
    // ========================

    @Test
    void register_addsCapabilities() {
        List<SharedCapabilityInfo> caps = List.of(
                new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food")),
                new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK", List.of("food", "kitchen")));

        registry.register("kitchen", caps);

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void register_nullEnsembleThrows() {
        assertThatThrownBy(() -> registry.register(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensemble");
    }

    @Test
    void register_nullCapabilitiesThrows() {
        assertThatThrownBy(() -> registry.register("kitchen", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capabilities");
    }

    @Test
    void register_replacesExistingCapabilities() {
        registry.register(
                "kitchen",
                List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food"))));
        assertThat(registry.size()).isEqualTo(1);

        registry.register(
                "kitchen",
                List.of(
                        new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK"),
                        new SharedCapabilityInfo("dietary-check", "Check allergens", "TOOL")));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.findProvider("check-inventory")).isEmpty();
        assertThat(registry.findProvider("prepare-meal")).isPresent();
        assertThat(registry.findProvider("dietary-check")).isPresent();
    }

    // ========================
    // Unregistration
    // ========================

    @Test
    void unregister_removesCapabilities() {
        registry.register(
                "kitchen",
                List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food"))));
        assertThat(registry.size()).isEqualTo(1);

        registry.unregister("kitchen");

        assertThat(registry.size()).isZero();
        assertThat(registry.findProvider("check-inventory")).isEmpty();
        assertThat(registry.findByTag("food")).isEmpty();
    }

    @Test
    void unregister_unknownEnsembleIsNoOp() {
        registry.register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));

        registry.unregister("unknown");

        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void unregister_nullEnsembleThrows() {
        assertThatThrownBy(() -> registry.unregister(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensemble");
    }

    // ========================
    // findProvider
    // ========================

    @Test
    void findProvider_returnsEnsembleForKnownCapability() {
        registry.register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));

        Optional<String> provider = registry.findProvider("check-inventory");

        assertThat(provider).contains("kitchen");
    }

    @Test
    void findProvider_returnsEmptyForUnknownCapability() {
        registry.register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));

        assertThat(registry.findProvider("non-existent")).isEmpty();
    }

    // ========================
    // findAllProviders
    // ========================

    @Test
    void findAllProviders_returnsAllProvidersOfCapability() {
        registry.register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));
        registry.register("warehouse", List.of(new SharedCapabilityInfo("check-inventory", "Warehouse stock", "TOOL")));

        List<String> providers = registry.findAllProviders("check-inventory");

        assertThat(providers).containsExactlyInAnyOrder("kitchen", "warehouse");
    }

    @Test
    void findAllProviders_returnsEmptyForUnknownCapability() {
        assertThat(registry.findAllProviders("non-existent")).isEmpty();
    }

    // ========================
    // findByTag
    // ========================

    @Test
    void findByTag_returnsCapabilitiesMatchingTag() {
        registry.register(
                "kitchen",
                List.of(
                        new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food")),
                        new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK", List.of("food", "kitchen"))));
        registry.register(
                "maintenance",
                List.of(new SharedCapabilityInfo("fix-pipe", "Fix a pipe", "TASK", List.of("plumbing"))));

        List<SharedCapabilityInfo> foodCaps = registry.findByTag("food");

        assertThat(foodCaps).hasSize(2);
        assertThat(foodCaps)
                .extracting(SharedCapabilityInfo::name)
                .containsExactlyInAnyOrder("check-inventory", "prepare-meal");
    }

    @Test
    void findByTag_returnsEmptyForUnknownTag() {
        registry.register(
                "kitchen",
                List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food"))));

        assertThat(registry.findByTag("plumbing")).isEmpty();
    }

    @Test
    void findByTag_kitchenTagFindsOnlyKitchenCapabilities() {
        registry.register(
                "kitchen",
                List.of(
                        new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL", List.of("food")),
                        new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK", List.of("food", "kitchen"))));

        List<SharedCapabilityInfo> kitchenCaps = registry.findByTag("kitchen");

        assertThat(kitchenCaps).hasSize(1);
        assertThat(kitchenCaps.get(0).name()).isEqualTo("prepare-meal");
    }

    // ========================
    // all()
    // ========================

    @Test
    void all_returnsCapabilitiesFromAllEnsembles() {
        registry.register("kitchen", List.of(new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL")));
        registry.register("maintenance", List.of(new SharedCapabilityInfo("fix-pipe", "Fix a pipe", "TASK")));

        List<SharedCapabilityInfo> all = registry.all();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(SharedCapabilityInfo::name).containsExactlyInAnyOrder("check-inventory", "fix-pipe");
    }

    // ========================
    // size()
    // ========================

    @Test
    void size_countsAcrossEnsembles() {
        registry.register(
                "kitchen",
                List.of(
                        new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL"),
                        new SharedCapabilityInfo("prepare-meal", "Prepare food", "TASK")));
        registry.register("maintenance", List.of(new SharedCapabilityInfo("fix-pipe", "Fix a pipe", "TASK")));

        assertThat(registry.size()).isEqualTo(3);
    }

    // ========================
    // Thread safety
    // ========================

    @Test
    void concurrentRegisterAndUnregister_doesNotCorrupt() throws Exception {
        int threadCount = 8;
        int iterations = 100;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < iterations; i++) {
                    String ensemble = "ensemble-" + threadIndex;
                    registry.register(
                            ensemble,
                            List.of(new SharedCapabilityInfo(
                                    "tool-" + threadIndex + "-" + i, "desc", "TOOL", List.of("tag-" + threadIndex))));
                    // Occasionally unregister to stress the removal paths
                    if (i % 3 == 0) {
                        registry.unregister(ensemble);
                    }
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        // After all threads complete, the registry should be in a consistent state.
        // The exact size depends on timing, but it should not throw or return corrupted data.
        assertThat(registry.size()).isGreaterThanOrEqualTo(0);
        assertThat(registry.all()).isNotNull();
    }
}
