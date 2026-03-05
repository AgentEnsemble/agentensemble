package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryToolTest {

    private MemoryStore store;
    private MemoryTool tool;

    @BeforeEach
    void setUp() {
        store = MemoryStore.inMemory();
        tool = MemoryTool.of("research", store);
    }

    // ========================
    // factory validation
    // ========================

    @Test
    void testOf_nullScope_throwsException() {
        assertThatThrownBy(() -> MemoryTool.of(null, store)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOf_blankScope_throwsException() {
        assertThatThrownBy(() -> MemoryTool.of("  ", store)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOf_nullStore_throwsException() {
        assertThatThrownBy(() -> MemoryTool.of("research", null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // getScope()
    // ========================

    @Test
    void testGetScope_returnsConfiguredScope() {
        assertThat(tool.getScope()).isEqualTo("research");
    }

    // ========================
    // storeMemory()
    // ========================

    @Test
    void testStoreMemory_validKeyValue_storesEntry() {
        String result = tool.storeMemory("AI trends", "AI is accelerating in 2026");

        assertThat(result).contains("AI trends");
        // Verify entry was stored in the backing store
        var entries = store.retrieve("research", "AI", 5);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent()).contains("AI trends: AI is accelerating in 2026");
    }

    @Test
    void testStoreMemory_nullKey_returnsErrorMessage() {
        String result = tool.storeMemory(null, "value");

        assertThat(result).startsWith("Error:");
    }

    @Test
    void testStoreMemory_blankKey_returnsErrorMessage() {
        String result = tool.storeMemory("  ", "value");

        assertThat(result).startsWith("Error:");
    }

    @Test
    void testStoreMemory_nullValue_returnsErrorMessage() {
        String result = tool.storeMemory("key", null);

        assertThat(result).startsWith("Error:");
    }

    @Test
    void testStoreMemory_blankValue_returnsErrorMessage() {
        String result = tool.storeMemory("key", "  ");

        assertThat(result).startsWith("Error:");
    }

    // ========================
    // retrieveMemory()
    // ========================

    @Test
    void testRetrieveMemory_existingEntries_returnsFormatted() {
        tool.storeMemory("finding1", "Neural networks are trending");
        tool.storeMemory("finding2", "LLMs are transforming software");

        String result = tool.retrieveMemory("AI research");

        assertThat(result).contains("Retrieved memories:");
        assertThat(result).contains("finding1: Neural networks are trending");
    }

    @Test
    void testRetrieveMemory_emptyStore_returnsNotFound() {
        String result = tool.retrieveMemory("query");

        assertThat(result).contains("No relevant memories found");
    }

    @Test
    void testRetrieveMemory_nullQuery_returnsNoQuery() {
        String result = tool.retrieveMemory(null);

        assertThat(result).contains("No query provided");
    }

    @Test
    void testRetrieveMemory_blankQuery_returnsNoQuery() {
        String result = tool.retrieveMemory("   ");

        assertThat(result).contains("No query provided");
    }

    // ========================
    // scope isolation
    // ========================

    @Test
    void testScopeIsolation_differentScopeTools_doNotShareEntries() {
        MemoryTool toolA = MemoryTool.of("scope-a", store);
        MemoryTool toolB = MemoryTool.of("scope-b", store);

        toolA.storeMemory("key", "value in A");

        String resultB = toolB.retrieveMemory("query");
        assertThat(resultB).contains("No relevant memories found");
    }
}
