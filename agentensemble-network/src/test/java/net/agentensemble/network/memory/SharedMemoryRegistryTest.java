package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.memory.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedMemoryRegistry}.
 */
class SharedMemoryRegistryTest {

    private SharedMemoryRegistry registry;
    private SharedMemory sharedMemory;

    @BeforeEach
    void setUp() {
        registry = new SharedMemoryRegistry();
        sharedMemory = SharedMemory.builder().store(MemoryStore.inMemory()).build();
    }

    @Test
    void registerAndGet() {
        registry.register("context", sharedMemory);

        assertThat(registry.get("context")).isSameAs(sharedMemory);
    }

    @Test
    void contains_returnsTrueWhenRegistered() {
        registry.register("context", sharedMemory);

        assertThat(registry.contains("context")).isTrue();
    }

    @Test
    void contains_returnsFalseWhenNotRegistered() {
        assertThat(registry.contains("missing")).isFalse();
    }

    @Test
    void names_returnsAllRegisteredNames() {
        SharedMemory sm2 = SharedMemory.builder().store(MemoryStore.inMemory()).build();

        registry.register("context", sharedMemory);
        registry.register("preferences", sm2);

        assertThat(registry.names()).containsExactlyInAnyOrder("context", "preferences");
    }

    @Test
    void names_returnsImmutableSet() {
        registry.register("context", sharedMemory);

        assertThat(registry.names()).isUnmodifiable();
    }

    @Test
    void duplicateName_throwsIllegalArgument() {
        registry.register("context", sharedMemory);

        SharedMemory another =
                SharedMemory.builder().store(MemoryStore.inMemory()).build();

        assertThatThrownBy(() -> registry.register("context", another))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context")
                .hasMessageContaining("already registered");
    }

    @Test
    void get_missingName_throwsIllegalArgument() {
        assertThatThrownBy(() -> registry.get("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void register_nullName_throwsNPE() {
        assertThatThrownBy(() -> registry.register(null, sharedMemory))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void register_nullSharedMemory_throwsNPE() {
        assertThatThrownBy(() -> registry.register("context", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sharedMemory");
    }

    @Test
    void get_nullName_throwsNPE() {
        assertThatThrownBy(() -> registry.get(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }
}
