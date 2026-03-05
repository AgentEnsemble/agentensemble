package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MemoryStore#inMemory()} factory and the {@link InMemoryStore}
 * implementation.
 */
class MemoryStoreTest {

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = MemoryStore.inMemory();
    }

    private MemoryEntry entry(String content) {
        return MemoryEntry.builder()
                .content(content)
                .storedAt(Instant.now())
                .metadata(Map.of(MemoryEntry.META_AGENT_ROLE, "Agent"))
                .build();
    }

    // ========================
    // factory
    // ========================

    @Test
    void testInMemory_returnsNonNull() {
        assertThat(MemoryStore.inMemory()).isNotNull();
    }

    @Test
    void testInMemory_eachCallCreatesNewInstance() {
        MemoryStore s1 = MemoryStore.inMemory();
        MemoryStore s2 = MemoryStore.inMemory();

        s1.store("scope1", entry("Only in s1"));

        // s2 should not see s1's entries
        assertThat(s2.retrieve("scope1", "query", 10)).isEmpty();
    }

    // ========================
    // store() validation
    // ========================

    @Test
    void testStore_nullScope_throwsException() {
        assertThatThrownBy(() -> store.store(null, entry("content"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testStore_blankScope_throwsException() {
        assertThatThrownBy(() -> store.store("  ", entry("content"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testStore_nullEntry_throwsException() {
        assertThatThrownBy(() -> store.store("scope", null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // store() + retrieve() happy path
    // ========================

    @Test
    void testStoreAndRetrieve_singleEntry_returnsIt() {
        store.store("research", entry("AI trends 2026"));

        List<MemoryEntry> results = store.retrieve("research", "AI", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("AI trends 2026");
    }

    @Test
    void testStoreAndRetrieve_multipleEntries_returnsUpToMaxResults() {
        for (int i = 1; i <= 5; i++) {
            store.store("scope", entry("Entry " + i));
        }

        List<MemoryEntry> results = store.retrieve("scope", "query", 3);

        assertThat(results).hasSize(3);
    }

    @Test
    void testRetrieve_emptyScope_returnsEmpty() {
        List<MemoryEntry> results = store.retrieve("nonexistent", "query", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void testRetrieve_scopeIsolation_differentScopeNotReturned() {
        store.store("scope-a", entry("Entry in A"));
        store.store("scope-b", entry("Entry in B"));

        List<MemoryEntry> resultsA = store.retrieve("scope-a", "query", 5);
        List<MemoryEntry> resultsB = store.retrieve("scope-b", "query", 5);

        assertThat(resultsA).hasSize(1);
        assertThat(resultsA.get(0).getContent()).isEqualTo("Entry in A");
        assertThat(resultsB).hasSize(1);
        assertThat(resultsB.get(0).getContent()).isEqualTo("Entry in B");
    }

    @Test
    void testRetrieve_returnsInInsertionOrder_mostRecentLast() {
        store.store("scope", entry("First"));
        store.store("scope", entry("Second"));
        store.store("scope", entry("Third"));

        List<MemoryEntry> results = store.retrieve("scope", "query", 10);

        assertThat(results.stream().map(MemoryEntry::getContent)).containsExactly("First", "Second", "Third");
    }

    @Test
    void testRetrieve_maxResultsLessThanTotal_returnsNewest() {
        store.store("scope", entry("Oldest"));
        store.store("scope", entry("Middle"));
        store.store("scope", entry("Newest"));

        List<MemoryEntry> results = store.retrieve("scope", "query", 2);

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(MemoryEntry::getContent)).containsExactly("Middle", "Newest");
    }

    // ========================
    // retrieve() validation
    // ========================

    @Test
    void testRetrieve_nullScope_throwsException() {
        assertThatThrownBy(() -> store.retrieve(null, "query", 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRetrieve_zeroMaxResults_throwsException() {
        assertThatThrownBy(() -> store.retrieve("scope", "query", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRetrieve_nullQuery_returnsRecentEntries() {
        store.store("scope", entry("Entry 1"));
        store.store("scope", entry("Entry 2"));

        // Null query still returns entries (most recent, no semantic search)
        List<MemoryEntry> results = store.retrieve("scope", null, 5);

        assertThat(results).hasSize(2);
    }

    // ========================
    // evict()
    // ========================

    @Test
    void testEvict_keepLastEntries_retainsExpected() {
        store.store("scope", entry("First"));
        store.store("scope", entry("Second"));
        store.store("scope", entry("Third"));
        store.store("scope", entry("Fourth"));

        store.evict("scope", EvictionPolicy.keepLastEntries(2));

        List<MemoryEntry> results = store.retrieve("scope", "query", 10);

        assertThat(results).hasSize(2);
        assertThat(results.stream().map(MemoryEntry::getContent)).containsExactly("Third", "Fourth");
    }

    @Test
    void testEvict_emptyScope_noException() {
        // Should not throw when scope doesn't exist
        store.evict("nonexistent", EvictionPolicy.keepLastEntries(5));
    }

    @Test
    void testEvict_nullScope_throwsException() {
        assertThatThrownBy(() -> store.evict(null, EvictionPolicy.keepLastEntries(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEvict_nullPolicy_throwsException() {
        assertThatThrownBy(() -> store.evict("scope", null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testEvict_keepEntriesWithin_removesOldEntries() {
        // Store an old entry
        MemoryEntry oldEntry = MemoryEntry.builder()
                .content("Old entry")
                .storedAt(Instant.now().minus(Duration.ofDays(2)))
                .metadata(Map.of())
                .build();
        MemoryEntry recentEntry = MemoryEntry.builder()
                .content("Recent entry")
                .storedAt(Instant.now().minusSeconds(30))
                .metadata(Map.of())
                .build();

        store.store("scope", oldEntry);
        store.store("scope", recentEntry);

        store.evict("scope", EvictionPolicy.keepEntriesWithin(Duration.ofHours(1)));

        List<MemoryEntry> results = store.retrieve("scope", "query", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("Recent entry");
    }

    // ========================
    // Persistence across multiple retrieve calls
    // ========================

    @Test
    void testStore_multipleRetrieves_storeIsPersistent() {
        store.store("scope", entry("Persistent entry"));

        List<MemoryEntry> first = store.retrieve("scope", "query", 5);
        List<MemoryEntry> second = store.retrieve("scope", "query", 5);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0).getContent()).isEqualTo(second.get(0).getContent());
    }

    // ========================
    // Cross-run persistence simulation
    // ========================

    @Test
    void testCrossRunPersistence_secondRunSeesFirstRunOutput() {
        // Simulate first ensemble run storing an output
        MemoryEntry run1Output = MemoryEntry.builder()
                .content("Run 1 research output: AI trends are accelerating.")
                .storedAt(Instant.now())
                .metadata(Map.of(
                        MemoryEntry.META_AGENT_ROLE, "Researcher",
                        MemoryEntry.META_TASK_DESCRIPTION, "Research AI trends"))
                .build();
        store.store("research", run1Output);

        // Simulate second ensemble run retrieving before execution
        List<MemoryEntry> run2Input = store.retrieve("research", "Research AI trends", 5);

        assertThat(run2Input).hasSize(1);
        assertThat(run2Input.get(0).getContent()).contains("AI trends are accelerating");
    }

    // ========================
    // Scope isolation
    // ========================

    @Test
    void testScopeIsolation_cannotReadFromUndeclaredScope() {
        store.store("task-a-scope", entry("Task A output"));

        // Task B only reads from its own scope, not task-a-scope
        List<MemoryEntry> taskBResults = store.retrieve("task-b-scope", "query", 5);

        assertThat(taskBResults).isEmpty();
    }

    // ========================
    // Empty scope on first run
    // ========================

    @Test
    void testEmptyScope_firstRun_returnsEmpty() {
        List<MemoryEntry> results = store.retrieve("brand-new-scope", "query", 5);

        assertThat(results).isEmpty();
    }
}
