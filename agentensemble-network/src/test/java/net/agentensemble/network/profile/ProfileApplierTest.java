package net.agentensemble.network.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.network.memory.SharedMemory;
import net.agentensemble.network.memory.SharedMemoryRegistry;
import net.agentensemble.web.protocol.ProfileAppliedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProfileApplier} with mocked dependencies.
 */
class ProfileApplierTest {

    private SharedMemoryRegistry registry;
    private AtomicReference<ProfileAppliedMessage> broadcast;
    private ProfileApplier applier;

    @BeforeEach
    void setUp() {
        registry = mock(SharedMemoryRegistry.class);
        broadcast = new AtomicReference<>();
        applier = new ProfileApplier(registry, broadcast::set);
    }

    @Test
    void apply_broadcastsProfileAppliedMessage() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("peak-hours")
                .ensemble("kitchen", Capacity.replicas(4).maxConcurrent(50))
                .ensemble("front-desk", Capacity.replicas(2).maxConcurrent(20))
                .build();

        applier.apply(profile);

        ProfileAppliedMessage msg = broadcast.get();
        assertThat(msg).isNotNull();
        assertThat(msg.profileName()).isEqualTo("peak-hours");
        assertThat(msg.capacities()).hasSize(2);
        assertThat(msg.capacities().get("kitchen").replicas()).isEqualTo(4);
        assertThat(msg.capacities().get("kitchen").maxConcurrent()).isEqualTo(50);
        assertThat(msg.capacities().get("front-desk").replicas()).isEqualTo(2);
        assertThat(msg.appliedAt()).isNotNull();
    }

    @Test
    void apply_withPreloads_storesIntoSharedMemory() {
        SharedMemory sharedMemory = mock(SharedMemory.class);
        when(registry.contains("inventory")).thenReturn(true);
        when(registry.get("inventory")).thenReturn(sharedMemory);

        NetworkProfile profile = NetworkProfile.builder()
                .name("sporting-event")
                .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
                .preload("kitchen", "inventory", "Extra beer and ice stocked")
                .build();

        applier.apply(profile);

        verify(sharedMemory).store(eq("inventory"), any(MemoryEntry.class));
    }

    @Test
    void apply_missingSharedMemoryScope_logsWarningDoesNotThrow() {
        when(registry.contains("missing-scope")).thenReturn(false);

        NetworkProfile profile = NetworkProfile.builder()
                .name("test")
                .preload("kitchen", "missing-scope", "content")
                .build();

        // Should not throw
        applier.apply(profile);

        // Broadcast should still happen
        assertThat(broadcast.get()).isNotNull();
        assertThat(broadcast.get().profileName()).isEqualTo("test");

        // Should never try to get the missing scope
        verify(registry, never()).get("missing-scope");
    }

    @Test
    void apply_profileWithNoPreloads_stillBroadcasts() {
        NetworkProfile profile = NetworkProfile.builder().name("minimal").build();

        applier.apply(profile);

        ProfileAppliedMessage msg = broadcast.get();
        assertThat(msg).isNotNull();
        assertThat(msg.profileName()).isEqualTo("minimal");
        assertThat(msg.capacities()).isEmpty();
    }

    @Test
    void apply_dormantCapacity_reflectedInMessage() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("quiet-night")
                .ensemble("maintenance", new Capacity(0, 0, true))
                .build();

        applier.apply(profile);

        ProfileAppliedMessage msg = broadcast.get();
        assertThat(msg.capacities().get("maintenance").dormant()).isTrue();
        assertThat(msg.capacities().get("maintenance").replicas()).isZero();
    }
}
