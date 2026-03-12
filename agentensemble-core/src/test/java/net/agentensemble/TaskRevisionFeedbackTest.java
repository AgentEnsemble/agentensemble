package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;

import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Task#withRevisionFeedback(String, String, int)} and the
 * revision feedback fields added for phase-retry support.
 */
class TaskRevisionFeedbackTest {

    private static Task baseTask() {
        return Task.builder()
                .description("Research the topic")
                .expectedOutput("A detailed report")
                .handler(ctx -> ToolResult.success("output"))
                .build();
    }

    // ========================
    // Default field values
    // ========================

    @Test
    void newTask_revisionFeedback_isNull() {
        assertThat(baseTask().getRevisionFeedback()).isNull();
    }

    @Test
    void newTask_priorAttemptOutput_isNull() {
        assertThat(baseTask().getPriorAttemptOutput()).isNull();
    }

    @Test
    void newTask_attemptNumber_isZero() {
        assertThat(baseTask().getAttemptNumber()).isEqualTo(0);
    }

    // ========================
    // withRevisionFeedback
    // ========================

    @Test
    void withRevisionFeedback_setsRevisionFields() {
        Task original = baseTask();
        Task revised = original.withRevisionFeedback("needs improvement", "old output", 1);

        assertThat(revised.getRevisionFeedback()).isEqualTo("needs improvement");
        assertThat(revised.getPriorAttemptOutput()).isEqualTo("old output");
        assertThat(revised.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void withRevisionFeedback_preservesDescription() {
        Task original = baseTask();
        Task revised = original.withRevisionFeedback("feedback", "prior", 1);

        assertThat(revised.getDescription()).isEqualTo(original.getDescription());
    }

    @Test
    void withRevisionFeedback_preservesExpectedOutput() {
        Task original = baseTask();
        Task revised = original.withRevisionFeedback("feedback", "prior", 1);

        assertThat(revised.getExpectedOutput()).isEqualTo(original.getExpectedOutput());
    }

    @Test
    void withRevisionFeedback_preservesHandler() {
        Task original = baseTask();
        Task revised = original.withRevisionFeedback("feedback", "prior", 1);

        assertThat(revised.getHandler()).isSameAs(original.getHandler());
    }

    @Test
    void withRevisionFeedback_doesNotMutateOriginal() {
        Task original = baseTask();
        original.withRevisionFeedback("feedback", "prior", 1);

        // Original must be unmodified (Task is immutable).
        assertThat(original.getRevisionFeedback()).isNull();
        assertThat(original.getPriorAttemptOutput()).isNull();
        assertThat(original.getAttemptNumber()).isEqualTo(0);
    }

    @Test
    void withRevisionFeedback_nullFeedback_treatedAsEmptyString() {
        Task revised = baseTask().withRevisionFeedback(null, "prior", 1);
        assertThat(revised.getRevisionFeedback()).isEqualTo("");
    }

    @Test
    void withRevisionFeedback_nullPriorOutput_staysNull() {
        Task revised = baseTask().withRevisionFeedback("feedback", null, 1);
        assertThat(revised.getPriorAttemptOutput()).isNull();
    }

    @Test
    void withRevisionFeedback_attemptZero_isValid() {
        Task revised = baseTask().withRevisionFeedback("feedback", null, 0);
        assertThat(revised.getAttemptNumber()).isEqualTo(0);
    }

    @Test
    void withRevisionFeedback_canBeCalledChained_incrementsAttempt() {
        Task attempt1 = baseTask().withRevisionFeedback("first feedback", "first output", 1);
        Task attempt2 = attempt1.withRevisionFeedback("second feedback", "second output", 2);

        assertThat(attempt2.getRevisionFeedback()).isEqualTo("second feedback");
        assertThat(attempt2.getPriorAttemptOutput()).isEqualTo("second output");
        assertThat(attempt2.getAttemptNumber()).isEqualTo(2);
        // attempt1 is unchanged
        assertThat(attempt1.getRevisionFeedback()).isEqualTo("first feedback");
        assertThat(attempt1.getAttemptNumber()).isEqualTo(1);
    }
}
