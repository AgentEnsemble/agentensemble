package net.agentensemble.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link FakeTaskExecutor}. */
class FakeTaskExecutorTest {

    // ========================
    // alwaysReturns factory
    // ========================

    @Test
    void alwaysReturns_returnsThatOutputForAnyRequest() {
        var fake = FakeTaskExecutor.alwaysReturns("Fixed output.");

        var result = fake.execute(TaskRequest.of("Research AI", "A summary"));

        assertThat(result.output()).isEqualTo("Fixed output.");
        assertThat(result.isComplete()).isTrue();
        assertThat(result.exitReason()).isEqualTo("COMPLETED");
        assertThat(result.toolCallCount()).isZero();
        assertThat(result.durationMs()).isPositive();
    }

    @Test
    void alwaysReturns_nullOutput_throwsNullPointer() {
        assertThatThrownBy(() -> FakeTaskExecutor.alwaysReturns(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // execute() null validation
    // ========================

    @Test
    void execute_nullRequest_throwsNullPointer() {
        var fake = FakeTaskExecutor.alwaysReturns("x");

        assertThatThrownBy(() -> fake.execute((TaskRequest) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void execute_withConsumer_nullRequest_throwsNullPointer() {
        var fake = FakeTaskExecutor.alwaysReturns("x");

        assertThatThrownBy(() -> fake.execute(null, obj -> {})).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // builder -- whenDescriptionContains
    // ========================

    @Test
    void builder_whenDescriptionContains_matchesSubstring() {
        var fake = FakeTaskExecutor.builder()
                .whenDescriptionContains("Research", "Research result.")
                .whenDescriptionContains("Write", "Article written.")
                .build();

        var researchResult = fake.execute(TaskRequest.of("Research AI trends", "A summary"));
        var writeResult = fake.execute(TaskRequest.of("Write an article", "An article"));

        assertThat(researchResult.output()).isEqualTo("Research result.");
        assertThat(writeResult.output()).isEqualTo("Article written.");
    }

    @Test
    void builder_firstMatchWins_whenMultipleRulesMatch() {
        var fake = FakeTaskExecutor.builder()
                .whenDescriptionContains("Research", "First match.")
                .whenDescriptionContains("Research AI", "Second match -- should not be returned.")
                .build();

        var result = fake.execute(TaskRequest.of("Research AI trends", "A summary"));

        assertThat(result.output()).isEqualTo("First match.");
    }

    @Test
    void builder_noMatchUsesDefaultOutput() {
        var fake = FakeTaskExecutor.builder()
                .whenDescriptionContains("Research", "Research result.")
                .defaultOutput("No match default.")
                .build();

        var result = fake.execute(TaskRequest.of("Something completely different", "Output"));

        assertThat(result.output()).isEqualTo("No match default.");
    }

    @Test
    void builder_defaultOutputWithoutRules_alwaysReturnsDefault() {
        var fake = FakeTaskExecutor.builder().defaultOutput("Always this.").build();

        assertThat(fake.execute(TaskRequest.of("Task A", "Out")).output()).isEqualTo("Always this.");
        assertThat(fake.execute(TaskRequest.of("Task B", "Out")).output()).isEqualTo("Always this.");
    }

    // ========================
    // builder -- whenAgentRole
    // ========================

    @Test
    void builder_whenAgentRole_matchesOnRole() {
        var fake = FakeTaskExecutor.builder()
                .whenAgentRole("Researcher", "Research output.")
                .whenAgentRole("Writer", "Writing output.")
                .defaultOutput("Fallback.")
                .build();

        var researchRequest = TaskRequest.builder()
                .description("Do research")
                .expectedOutput("Summary")
                .agent(AgentSpec.of("Researcher", "Research"))
                .build();
        var writeRequest = TaskRequest.builder()
                .description("Write something")
                .expectedOutput("Article")
                .agent(AgentSpec.of("Writer", "Write"))
                .build();
        var noAgentRequest = TaskRequest.of("No agent task", "Output");

        assertThat(fake.execute(researchRequest).output()).isEqualTo("Research output.");
        assertThat(fake.execute(writeRequest).output()).isEqualTo("Writing output.");
        assertThat(fake.execute(noAgentRequest).output()).isEqualTo("Fallback.");
    }

    // ========================
    // builder -- whenDescription predicate
    // ========================

    @Test
    void builder_whenDescriptionPredicate_matchesOnCustomLogic() {
        var fake = FakeTaskExecutor.builder()
                .whenDescription(d -> d.startsWith("Research"), "Research output.")
                .defaultOutput("Fallback.")
                .build();

        assertThat(fake.execute(TaskRequest.of("Research AI", "Out")).output()).isEqualTo("Research output.");
        assertThat(fake.execute(TaskRequest.of("Write about AI", "Out")).output())
                .isEqualTo("Fallback.");
    }

    // ========================
    // heartbeat consumer
    // ========================

    @Test
    void execute_withHeartbeatConsumer_doesNotEmitAnyEvents() {
        var fake = FakeTaskExecutor.alwaysReturns("Done.");
        var captured = new java.util.ArrayList<>();

        fake.execute(TaskRequest.of("Task", "Output"), captured::add);

        assertThat(captured).isEmpty(); // FakeTaskExecutor does not fire heartbeats
    }

    @Test
    void execute_withNullConsumer_runsWithoutError() {
        var fake = FakeTaskExecutor.alwaysReturns("Done.");

        assertThat(fake.execute(TaskRequest.of("Task", "Output"), null).isComplete())
                .isTrue();
    }

    // ========================
    // context and inputs (no special handling needed -- fake ignores them)
    // ========================

    @Test
    void execute_withContextAndInputs_runsSuccessfully() {
        var fake = FakeTaskExecutor.alwaysReturns("Output with context.");

        var request = TaskRequest.builder()
                .description("Write about {topic} using: {research}")
                .expectedOutput("Article")
                .context(Map.of("research", "AI is advancing."))
                .inputs(Map.of("topic", "Artificial Intelligence"))
                .build();

        assertThat(fake.execute(request).output()).isEqualTo("Output with context.");
    }

    // ========================
    // is a subtype of TaskExecutor
    // ========================

    @Test
    void fakeTaskExecutor_isSubtypeOfTaskExecutor() {
        FakeTaskExecutor fake = FakeTaskExecutor.alwaysReturns("Output.");

        // Assignable to TaskExecutor -- key for constructor injection
        TaskExecutor executor = fake;
        assertThat(executor.execute(TaskRequest.of("Task", "Output")).isComplete())
                .isTrue();
    }

    // ========================
    // builder validation
    // ========================

    @Test
    void builder_nullSubstring_throwsNullPointer() {
        assertThatThrownBy(() -> FakeTaskExecutor.builder().whenDescriptionContains(null, "out"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullOutput_throwsNullPointer() {
        assertThatThrownBy(() -> FakeTaskExecutor.builder().whenDescriptionContains("x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullDefaultOutput_throwsNullPointer() {
        assertThatThrownBy(() -> FakeTaskExecutor.builder().defaultOutput(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ========================
    // result shape
    // ========================

    @Test
    void result_hasExpectedShape() {
        var result = FakeTaskExecutor.alwaysReturns("Test output.").execute(TaskRequest.of("Any task", "Any output"));

        assertThat(result.output()).isEqualTo("Test output.");
        assertThat(result.exitReason()).isEqualTo("COMPLETED");
        assertThat(result.toolCallCount()).isZero();
        assertThat(result.durationMs()).isEqualTo(1L);
        assertThat(result.isComplete()).isTrue();
    }
}
