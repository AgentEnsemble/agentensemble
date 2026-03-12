package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PhaseReview}: builder, static factories, and validation.
 */
class PhaseReviewTest {

    private static final Task DUMMY_TASK = Task.builder()
            .description("Evaluate the output")
            .expectedOutput("A decision")
            .handler(ctx -> ToolResult.success("APPROVE"))
            .build();

    // ========================
    // Static factories
    // ========================

    @Test
    void of_task_usesDefaultRetryLimits() {
        PhaseReview review = PhaseReview.of(DUMMY_TASK);

        assertThat(review.getTask()).isSameAs(DUMMY_TASK);
        assertThat(review.getMaxRetries()).isEqualTo(PhaseReview.DEFAULT_MAX_RETRIES);
        assertThat(review.getMaxPredecessorRetries()).isEqualTo(PhaseReview.DEFAULT_MAX_PREDECESSOR_RETRIES);
    }

    @Test
    void of_taskAndMaxRetries_overridesMaxRetries() {
        PhaseReview review = PhaseReview.of(DUMMY_TASK, 5);

        assertThat(review.getMaxRetries()).isEqualTo(5);
        assertThat(review.getMaxPredecessorRetries()).isEqualTo(PhaseReview.DEFAULT_MAX_PREDECESSOR_RETRIES);
    }

    @Test
    void of_nullTask_throwsValidation() {
        assertThatThrownBy(() -> PhaseReview.of(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("task must not be null");
    }

    // ========================
    // Builder
    // ========================

    @Test
    void builder_allFields() {
        PhaseReview review = PhaseReview.builder()
                .task(DUMMY_TASK)
                .maxRetries(3)
                .maxPredecessorRetries(1)
                .build();

        assertThat(review.getTask()).isSameAs(DUMMY_TASK);
        assertThat(review.getMaxRetries()).isEqualTo(3);
        assertThat(review.getMaxPredecessorRetries()).isEqualTo(1);
    }

    @Test
    void builder_defaultRetryLimitsApplied() {
        PhaseReview review = PhaseReview.builder().task(DUMMY_TASK).build();

        assertThat(review.getMaxRetries()).isEqualTo(PhaseReview.DEFAULT_MAX_RETRIES);
        assertThat(review.getMaxPredecessorRetries()).isEqualTo(PhaseReview.DEFAULT_MAX_PREDECESSOR_RETRIES);
    }

    @Test
    void builder_zeroMaxRetries_isValid() {
        PhaseReview review =
                PhaseReview.builder().task(DUMMY_TASK).maxRetries(0).build();
        assertThat(review.getMaxRetries()).isEqualTo(0);
    }

    @Test
    void builder_nullTask_throwsValidation() {
        assertThatThrownBy(() -> PhaseReview.builder().maxRetries(2).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("task must not be null");
    }

    @Test
    void builder_negativeMaxRetries_throwsValidation() {
        assertThatThrownBy(() ->
                        PhaseReview.builder().task(DUMMY_TASK).maxRetries(-1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxRetries must be >= 0");
    }

    @Test
    void builder_negativeMaxPredecessorRetries_throwsValidation() {
        assertThatThrownBy(() -> PhaseReview.builder()
                        .task(DUMMY_TASK)
                        .maxPredecessorRetries(-1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxPredecessorRetries must be >= 0");
    }

    // ========================
    // Constants
    // ========================

    @Test
    void defaultMaxRetries_is2() {
        assertThat(PhaseReview.DEFAULT_MAX_RETRIES).isEqualTo(2);
    }

    @Test
    void defaultMaxPredecessorRetries_is2() {
        assertThat(PhaseReview.DEFAULT_MAX_PREDECESSOR_RETRIES).isEqualTo(2);
    }
}
