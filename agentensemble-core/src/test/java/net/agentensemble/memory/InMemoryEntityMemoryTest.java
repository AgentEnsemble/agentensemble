package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryEntityMemoryTest {

    private InMemoryEntityMemory memory;

    @BeforeEach
    void setUp() {
        memory = new InMemoryEntityMemory();
    }

    // ========================
    // Initial state
    // ========================

    @Test
    void testNewMemory_isEmpty() {
        assertThat(memory.isEmpty()).isTrue();
        assertThat(memory.getAll()).isEmpty();
    }

    // ========================
    // put() and get()
    // ========================

    @Test
    void testPut_singleEntry_retrievable() {
        memory.put("OpenAI", "A US AI research lab");

        assertThat(memory.get("OpenAI")).isPresent().hasValue("A US AI research lab");
        assertThat(memory.isEmpty()).isFalse();
    }

    @Test
    void testPut_overwritesExisting() {
        memory.put("OpenAI", "old fact");
        memory.put("OpenAI", "new fact");

        assertThat(memory.get("OpenAI")).hasValue("new fact");
    }

    @Test
    void testPut_multipleEntities() {
        memory.put("OpenAI", "AI lab");
        memory.put("LangChain4j", "Java LLM library");

        assertThat(memory.getAll()).hasSize(2);
    }

    @Test
    void testPut_trimsEntityName() {
        memory.put("  OpenAI  ", "AI lab");

        assertThat(memory.get("OpenAI")).isPresent();
        assertThat(memory.get("  OpenAI  ")).isPresent();
    }

    @Test
    void testPut_nullEntityName_throwsException() {
        assertThatThrownBy(() -> memory.put(null, "fact")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testPut_blankEntityName_throwsException() {
        assertThatThrownBy(() -> memory.put("   ", "fact")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testPut_nullFact_throwsException() {
        assertThatThrownBy(() -> memory.put("OpenAI", null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // get()
    // ========================

    @Test
    void testGet_unknownEntity_returnsEmpty() {
        assertThat(memory.get("Unknown")).isEmpty();
    }

    @Test
    void testGet_nullKey_returnsEmpty() {
        assertThat(memory.get(null)).isEmpty();
    }

    @Test
    void testGet_trimsKey() {
        memory.put("OpenAI", "fact");

        assertThat(memory.get("  OpenAI  ")).isPresent().hasValue("fact");
    }

    // ========================
    // getAll()
    // ========================

    @Test
    void testGetAll_returnsUnmodifiableMap() {
        memory.put("Entity", "fact");
        var all = memory.getAll();

        assertThatThrownBy(() -> all.put("Other", "fact")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testGetAll_containsAllStoredEntities() {
        memory.put("A", "fact A");
        memory.put("B", "fact B");
        memory.put("C", "fact C");

        var all = memory.getAll();
        assertThat(all)
                .containsEntry("A", "fact A")
                .containsEntry("B", "fact B")
                .containsEntry("C", "fact C");
    }

    // ========================
    // isEmpty()
    // ========================

    @Test
    void testIsEmpty_afterAddingEntry_returnsFalse() {
        memory.put("Entity", "fact");

        assertThat(memory.isEmpty()).isFalse();
    }

    // ========================
    // Thread safety (basic check)
    // ========================

    @Test
    void testPut_concurrentWrites_doesNotThrow() throws InterruptedException {
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                memory.put("Entity" + i, "fact" + i);
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 100; i < 200; i++) {
                memory.put("Entity" + i, "fact" + i);
            }
        });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertThat(memory.getAll()).hasSize(200);
    }
}
