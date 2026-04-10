package net.agentensemble.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link FakeEnsembleExecutor}. */
class FakeEnsembleExecutorTest {

    // ========================
    // alwaysReturns factory
    // ========================

    @Test
    void alwaysReturns_singleTask_returnsConfiguredOutput() {
        var fake = FakeEnsembleExecutor.alwaysReturns("Pipeline done.");

        var result = fake.execute(EnsembleRequest.builder()
                .task(TaskRequest.of("Research AI", "A summary"))
                .build());

        assertThat(result.finalOutput()).isEqualTo("Pipeline done.");
        assertThat(result.isComplete()).isTrue();
        assertThat(result.exitReason()).isEqualTo("COMPLETED");
        assertThat(result.taskOutputs()).containsExactly("Pipeline done.");
    }

    @Test
    void alwaysReturns_multipleTasks_sameOutputForEachTask() {
        var fake = FakeEnsembleExecutor.alwaysReturns("Same for all.");

        var result = fake.execute(EnsembleRequest.builder()
                .task(TaskRequest.of("Task 1", "Output 1"))
                .task(TaskRequest.of("Task 2", "Output 2"))
                .build());

        assertThat(result.taskOutputs()).containsExactly("Same for all.", "Same for all.");
        assertThat(result.finalOutput()).isEqualTo("Same for all.");
    }

    @Test
    void alwaysReturns_nullOutput_throwsNullPointer() {
        assertThatThrownBy(() -> FakeEnsembleExecutor.alwaysReturns(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // execute() null and empty validation
    // ========================

    @Test
    void execute_nullRequest_throwsNullPointer() {
        var fake = FakeEnsembleExecutor.alwaysReturns("x");

        assertThatThrownBy(() -> fake.execute((EnsembleRequest) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void execute_emptyTaskList_throwsIllegalArgument() {
        var fake = FakeEnsembleExecutor.alwaysReturns("x");

        assertThatThrownBy(() -> fake.execute(EnsembleRequest.builder().build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // builder -- rule-based per-task matching
    // ========================

    @Test
    void builder_eachTaskMatchedIndependently() {
        var fake = FakeEnsembleExecutor.builder()
                .whenDescriptionContains("Research", "Research result: AI is growing.")
                .whenDescriptionContains("Write", "Final article: AI transforms society.")
                .defaultOutput("Fallback output.")
                .build();

        var request = EnsembleRequest.builder()
                .task(TaskRequest.of("Research AI trends", "A summary"))
                .task(TaskRequest.of("Write a blog post about AI", "A blog post"))
                .build();

        var result = fake.execute(request);

        assertThat(result.taskOutputs())
                .containsExactly("Research result: AI is growing.", "Final article: AI transforms society.");
        assertThat(result.finalOutput()).isEqualTo("Final article: AI transforms society.");
    }

    @Test
    void builder_lastTaskOutputBecomesFinalOutput() {
        var fake = FakeEnsembleExecutor.builder()
                .whenDescriptionContains("Step 1", "Step 1 output.")
                .whenDescriptionContains("Step 2", "Step 2 output.")
                .whenDescriptionContains("Step 3", "Step 3 -- final.")
                .build();

        var result = fake.execute(EnsembleRequest.builder()
                .task(TaskRequest.of("Step 1", "Out"))
                .task(TaskRequest.of("Step 2", "Out"))
                .task(TaskRequest.of("Step 3", "Out"))
                .build());

        assertThat(result.taskOutputs()).hasSize(3);
        assertThat(result.finalOutput()).isEqualTo("Step 3 -- final.");
    }

    @Test
    void builder_unmatchedTaskUsesDefaultOutput() {
        var fake = FakeEnsembleExecutor.builder()
                .whenDescriptionContains("Research", "Research output.")
                .defaultOutput("Default for unmatched.")
                .build();

        var result = fake.execute(EnsembleRequest.builder()
                .task(TaskRequest.of("Research AI", "Summary"))
                .task(TaskRequest.of("Completely unrelated task", "Output"))
                .build());

        assertThat(result.taskOutputs()).containsExactly("Research output.", "Default for unmatched.");
    }

    // ========================
    // heartbeat consumer
    // ========================

    @Test
    void execute_withHeartbeatConsumer_doesNotEmitAnyEvents() {
        var fake = FakeEnsembleExecutor.alwaysReturns("Done.");
        var captured = new java.util.ArrayList<>();

        fake.execute(
                EnsembleRequest.builder().task(TaskRequest.of("Task", "Output")).build(), captured::add);

        assertThat(captured).isEmpty();
    }

    @Test
    void execute_withNullConsumer_runsWithoutError() {
        var fake = FakeEnsembleExecutor.alwaysReturns("Done.");

        var result = fake.execute(
                EnsembleRequest.builder().task(TaskRequest.of("Task", "Output")).build(), null);

        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // result shape
    // ========================

    @Test
    void result_hasExpectedShape() {
        var result = FakeEnsembleExecutor.alwaysReturns("Output.")
                .execute(EnsembleRequest.builder()
                        .task(TaskRequest.of("Task", "Out"))
                        .build());

        assertThat(result.finalOutput()).isEqualTo("Output.");
        assertThat(result.exitReason()).isEqualTo("COMPLETED");
        assertThat(result.totalToolCalls()).isZero();
        assertThat(result.totalDurationMs()).isEqualTo(1L);
        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // is a subtype of EnsembleExecutor
    // ========================

    @Test
    void fakeEnsembleExecutor_isSubtypeOfEnsembleExecutor() {
        FakeEnsembleExecutor fake = FakeEnsembleExecutor.alwaysReturns("Done.");

        // Assignable to EnsembleExecutor -- key for constructor injection
        EnsembleExecutor executor = fake;
        var result = executor.execute(
                EnsembleRequest.builder().task(TaskRequest.of("Task", "Output")).build());
        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // builder validation
    // ========================

    @Test
    void builder_nullSubstring_throwsNullPointer() {
        assertThatThrownBy(() -> FakeEnsembleExecutor.builder().whenDescriptionContains(null, "out"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullDefaultOutput_throwsNullPointer() {
        assertThatThrownBy(() -> FakeEnsembleExecutor.builder().defaultOutput(null))
                .isInstanceOf(NullPointerException.class);
    }
}
