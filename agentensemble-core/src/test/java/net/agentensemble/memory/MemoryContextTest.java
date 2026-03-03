package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

class MemoryContextTest {

    private TaskOutput taskOutput(String raw, String role, String description) {
        return TaskOutput.builder()
                .raw(raw)
                .agentRole(role)
                .taskDescription(description)
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build();
    }

    // ========================
    // disabled()
    // ========================

    @Test
    void testDisabled_isNotActive() {
        MemoryContext ctx = MemoryContext.disabled();

        assertThat(ctx.isActive()).isFalse();
        assertThat(ctx.hasShortTerm()).isFalse();
        assertThat(ctx.hasLongTerm()).isFalse();
        assertThat(ctx.hasEntityMemory()).isFalse();
    }

    @Test
    void testDisabled_record_isNoOp() {
        MemoryContext ctx = MemoryContext.disabled();
        TaskOutput output = taskOutput("text", "Agent", "task");

        // Should not throw
        ctx.record(output);
        assertThat(ctx.getShortTermEntries()).isEmpty();
    }

    @Test
    void testDisabled_getShortTermEntries_isEmpty() {
        assertThat(MemoryContext.disabled().getShortTermEntries()).isEmpty();
    }

    @Test
    void testDisabled_queryLongTerm_isEmpty() {
        assertThat(MemoryContext.disabled().queryLongTerm("query")).isEmpty();
    }

    @Test
    void testDisabled_getEntityFacts_isEmpty() {
        assertThat(MemoryContext.disabled().getEntityFacts()).isEmpty();
    }

    @Test
    void testDisabled_isSameInstance() {
        assertThat(MemoryContext.disabled()).isSameAs(MemoryContext.disabled());
    }

    // ========================
    // from()
    // ========================

    @Test
    void testFrom_nullConfig_throwsException() {
        assertThatThrownBy(() -> MemoryContext.from(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testFrom_shortTermEnabled_isActive() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        assertThat(ctx.isActive()).isTrue();
        assertThat(ctx.hasShortTerm()).isTrue();
    }

    @Test
    void testFrom_longTermConfigured_hasLongTermTrue() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        EnsembleMemory config = EnsembleMemory.builder().longTerm(ltm).build();
        MemoryContext ctx = MemoryContext.from(config);

        assertThat(ctx.hasLongTerm()).isTrue();
    }

    @Test
    void testFrom_entityMemoryWithFacts_hasEntityMemoryTrue() {
        InMemoryEntityMemory em = new InMemoryEntityMemory();
        em.put("Entity", "fact");
        EnsembleMemory config = EnsembleMemory.builder().entityMemory(em).build();
        MemoryContext ctx = MemoryContext.from(config);

        assertThat(ctx.hasEntityMemory()).isTrue();
    }

    @Test
    void testFrom_entityMemoryEmpty_hasEntityMemoryFalse() {
        InMemoryEntityMemory em = new InMemoryEntityMemory(); // empty
        EnsembleMemory config = EnsembleMemory.builder().entityMemory(em).build();
        MemoryContext ctx = MemoryContext.from(config);

        // Entity memory is present but empty -- hasEntityMemory() returns false
        assertThat(ctx.hasEntityMemory()).isFalse();
    }

    // ========================
    // record() with short-term memory
    // ========================

    @Test
    void testRecord_withShortTerm_addsEntryToStm() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        ctx.record(taskOutput("Research complete", "Researcher", "Research AI"));

        List<MemoryEntry> entries = ctx.getShortTermEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getContent()).isEqualTo("Research complete");
        assertThat(entries.get(0).getAgentRole()).isEqualTo("Researcher");
        assertThat(entries.get(0).getTaskDescription()).isEqualTo("Research AI");
    }

    @Test
    void testRecord_multipleOutputs_addsAllToStm() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        ctx.record(taskOutput("Output 1", "Agent1", "Task 1"));
        ctx.record(taskOutput("Output 2", "Agent2", "Task 2"));
        ctx.record(taskOutput("Output 3", "Agent3", "Task 3"));

        assertThat(ctx.getShortTermEntries()).hasSize(3);
    }

    @Test
    void testRecord_nullOutput_isNoOp() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        // Should not throw
        ctx.record(null);
        assertThat(ctx.getShortTermEntries()).isEmpty();
    }

    // ========================
    // record() with long-term memory
    // ========================

    @Test
    void testRecord_withLongTerm_callsStore() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        EnsembleMemory config = EnsembleMemory.builder().longTerm(ltm).build();
        MemoryContext ctx = MemoryContext.from(config);

        ctx.record(taskOutput("Content", "Agent", "Task"));

        verify(ltm).store(any(MemoryEntry.class));
    }

    @Test
    void testRecord_withoutLongTerm_doesNotStoreToLongTerm() {
        // A MemoryContext configured with short-term only must not route records to any
        // long-term store. Assert on the observable state rather than using an unwired mock,
        // which would make verify(mock, never()) vacuously true regardless of behavior.
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        // No long-term memory is configured
        assertThat(ctx.hasLongTerm()).isFalse();

        ctx.record(taskOutput("Content", "Agent", "Task"));

        // Short-term was recorded
        assertThat(ctx.getShortTermEntries()).hasSize(1);
        // Long-term query returns empty (no long-term store)
        assertThat(ctx.queryLongTerm("Content")).isEmpty();
    }

    // ========================
    // queryLongTerm()
    // ========================

    @Test
    void testQueryLongTerm_withLongTerm_callsRetrieve() {
        LongTermMemory ltm = mock(LongTermMemory.class);
        when(ltm.retrieve(anyString(), anyInt()))
                .thenReturn(List.of(MemoryEntry.builder()
                        .content("past memory")
                        .agentRole("Agent")
                        .taskDescription("old task")
                        .timestamp(Instant.now())
                        .build()));

        EnsembleMemory config =
                EnsembleMemory.builder().longTerm(ltm).longTermMaxResults(3).build();
        MemoryContext ctx = MemoryContext.from(config);

        List<MemoryEntry> results = ctx.queryLongTerm("current task description");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).isEqualTo("past memory");
        verify(ltm).retrieve("current task description", 3);
    }

    @Test
    void testQueryLongTerm_noLongTerm_returnsEmpty() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        assertThat(ctx.queryLongTerm("query")).isEmpty();
    }

    // ========================
    // getEntityFacts()
    // ========================

    @Test
    void testGetEntityFacts_withFacts_returnsAll() {
        InMemoryEntityMemory em = new InMemoryEntityMemory();
        em.put("Company X", "A tech startup");
        em.put("Alice", "The lead engineer");
        EnsembleMemory config = EnsembleMemory.builder().entityMemory(em).build();
        MemoryContext ctx = MemoryContext.from(config);

        var facts = ctx.getEntityFacts();
        assertThat(facts).containsEntry("Company X", "A tech startup").containsEntry("Alice", "The lead engineer");
    }

    @Test
    void testGetEntityFacts_noEntityMemory_returnsEmpty() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();
        MemoryContext ctx = MemoryContext.from(config);

        assertThat(ctx.getEntityFacts()).isEmpty();
    }

    // ========================
    // Each run gets fresh short-term memory
    // ========================

    @Test
    void testFromCreatesNewStm_eachCallGivesFreshContext() {
        EnsembleMemory config = EnsembleMemory.builder().shortTerm(true).build();

        MemoryContext ctx1 = MemoryContext.from(config);
        MemoryContext ctx2 = MemoryContext.from(config);

        ctx1.record(taskOutput("output", "Agent", "task"));

        assertThat(ctx1.getShortTermEntries()).hasSize(1);
        assertThat(ctx2.getShortTermEntries()).isEmpty();
    }
}
