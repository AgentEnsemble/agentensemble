package net.agentensemble.network.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.network.memory.Consistency;
import net.agentensemble.network.memory.SharedMemory;
import net.agentensemble.network.memory.SharedMemoryRegistry;
import net.agentensemble.web.protocol.ProfileAppliedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration-style tests for {@link ProfileApplier} using real SharedMemory (EVENTUAL)
 * and in-memory MemoryStore to verify content is actually stored.
 */
class ProfileApplierPreloadTest {

    private SharedMemoryRegistry registry;
    private SharedMemory sharedMemory;
    private AtomicReference<ProfileAppliedMessage> broadcast;
    private ProfileApplier applier;

    @BeforeEach
    void setUp() {
        sharedMemory = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.EVENTUAL)
                .build();

        registry = new SharedMemoryRegistry();
        registry.register("inventory", sharedMemory);

        broadcast = new AtomicReference<>();
        applier = new ProfileApplier(registry, broadcast::set);
    }

    @Test
    void applyProfile_preloadsContentIntoSharedMemory() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("sporting-event")
                .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
                .preload("kitchen", "inventory", "Extra beer and ice stocked")
                .build();

        applier.apply(profile);

        List<MemoryEntry> entries = sharedMemory.retrieve("inventory", null, 10);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent()).isEqualTo("Extra beer and ice stocked");
    }

    @Test
    void applyProfile_multiplePreloadsToSameScope() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("big-event")
                .ensemble("kitchen", Capacity.replicas(5).maxConcurrent(200))
                .preload("kitchen", "inventory", "Extra beer stocked")
                .preload("kitchen", "inventory", "Extra ice stocked")
                .build();

        applier.apply(profile);

        List<MemoryEntry> entries = sharedMemory.retrieve("inventory", null, 10);
        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(MemoryEntry::getContent)
                .containsExactly("Extra beer stocked", "Extra ice stocked");
    }

    @Test
    void applyProfile_broadcastStillHappensAfterPreload() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("sporting-event")
                .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
                .preload("kitchen", "inventory", "Extra beer and ice stocked")
                .build();

        applier.apply(profile);

        ProfileAppliedMessage msg = broadcast.get();
        assertThat(msg).isNotNull();
        assertThat(msg.profileName()).isEqualTo("sporting-event");
        assertThat(msg.capacities()).containsKey("kitchen");
    }

    @Test
    void applyProfile_unregisteredScope_skipsPreload() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("test")
                .preload("kitchen", "unregistered-scope", "content")
                .build();

        // Should not throw
        applier.apply(profile);

        // Broadcast still happens
        assertThat(broadcast.get()).isNotNull();
    }
}
