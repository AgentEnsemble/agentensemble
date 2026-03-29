package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedMemory.Builder}.
 */
class SharedMemoryBuilderTest {

    @Test
    void build_withoutStore_throwsNPE() {
        assertThatThrownBy(() -> SharedMemory.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("store");
    }

    @Test
    void build_nullStore_throwsNPE() {
        assertThatThrownBy(() -> SharedMemory.builder().store(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("store");
    }

    @Test
    void build_lockedWithoutLockProvider_throwsIllegalState() {
        assertThatThrownBy(() -> SharedMemory.builder()
                        .store(MemoryStore.inMemory())
                        .consistency(Consistency.LOCKED)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockProvider");
    }

    @Test
    void build_lockedWithLockProvider_succeeds() {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.LOCKED)
                .lockProvider(LockProvider.inMemory())
                .build();

        assertThat(sm.consistency()).isEqualTo(Consistency.LOCKED);
        assertThat(sm.lockProvider()).isNotNull();
    }

    @Test
    void build_eventualDoesNotRequireLockProvider() {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.EVENTUAL)
                .build();

        assertThat(sm.consistency()).isEqualTo(Consistency.EVENTUAL);
    }

    @Test
    void build_optimisticDoesNotRequireLockProvider() {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.OPTIMISTIC)
                .build();

        assertThat(sm.consistency()).isEqualTo(Consistency.OPTIMISTIC);
    }

    @Test
    void build_externalDoesNotRequireLockProvider() {
        SharedMemory sm = SharedMemory.builder()
                .store(MemoryStore.inMemory())
                .consistency(Consistency.EXTERNAL)
                .build();

        assertThat(sm.consistency()).isEqualTo(Consistency.EXTERNAL);
    }

    @Test
    void build_defaultsToEventual() {
        SharedMemory sm = SharedMemory.builder().store(MemoryStore.inMemory()).build();

        assertThat(sm.consistency()).isEqualTo(Consistency.EVENTUAL);
    }

    @Test
    void builder_canBeReused() {
        SharedMemory.Builder builder = SharedMemory.builder().store(MemoryStore.inMemory());

        SharedMemory sm1 = builder.build();
        SharedMemory sm2 = builder.build();

        assertThat(sm1).isNotSameAs(sm2);
        assertThat(sm1.consistency()).isEqualTo(Consistency.EVENTUAL);
        assertThat(sm2.consistency()).isEqualTo(Consistency.EVENTUAL);
    }

    @Test
    void build_nullConsistency_throwsNPE() {
        assertThatThrownBy(() ->
                        SharedMemory.builder().store(MemoryStore.inMemory()).consistency(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consistency");
    }

    @Test
    void build_nullLockProvider_throwsNPE() {
        assertThatThrownBy(() -> SharedMemory.builder().lockProvider(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lockProvider");
    }
}
