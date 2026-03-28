package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReviewRequest} factory methods and accessors.
 */
class ReviewRequestTest {

    @Test
    void of4arg_setsAllFields() {
        ReviewRequest req =
                ReviewRequest.of("Research AI", "The output text", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5));

        assertThat(req.taskDescription()).isEqualTo("Research AI");
        assertThat(req.taskOutput()).isEqualTo("The output text");
        assertThat(req.timing()).isEqualTo(ReviewTiming.AFTER_EXECUTION);
        assertThat(req.timeout()).isEqualTo(Duration.ofMinutes(5));
        assertThat(req.onTimeoutAction()).isEqualTo(OnTimeoutAction.EXIT_EARLY); // default
        assertThat(req.prompt()).isNull();
    }

    @Test
    void of4arg_nullTaskOutput_normalisedToEmpty() {
        ReviewRequest req = ReviewRequest.of("Task", null, ReviewTiming.BEFORE_EXECUTION, null);
        assertThat(req.taskOutput()).isEqualTo("");
    }

    @Test
    void of4arg_nullTimeout_isNull() {
        ReviewRequest req = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, null);
        assertThat(req.timeout()).isNull();
    }

    @Test
    void of4arg_nullTaskDescription_throws() {
        assertThatThrownBy(() -> ReviewRequest.of(null, "out", ReviewTiming.AFTER_EXECUTION, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of4arg_nullTiming_throws() {
        assertThatThrownBy(() -> ReviewRequest.of("Task", "out", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of6arg_setsAllFields() {
        ReviewRequest req = ReviewRequest.of(
                "Task description",
                "Output text",
                ReviewTiming.DURING_EXECUTION,
                Duration.ofSeconds(30),
                OnTimeoutAction.CONTINUE,
                "Please review this carefully");

        assertThat(req.taskDescription()).isEqualTo("Task description");
        assertThat(req.taskOutput()).isEqualTo("Output text");
        assertThat(req.timing()).isEqualTo(ReviewTiming.DURING_EXECUTION);
        assertThat(req.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(req.onTimeoutAction()).isEqualTo(OnTimeoutAction.CONTINUE);
        assertThat(req.prompt()).isEqualTo("Please review this carefully");
    }

    @Test
    void of6arg_nullOnTimeoutAction_defaultsToExitEarly() {
        ReviewRequest req = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, null, null, null);
        assertThat(req.onTimeoutAction()).isEqualTo(OnTimeoutAction.EXIT_EARLY);
    }

    @Test
    void of6arg_nullPrompt_isNull() {
        ReviewRequest req =
                ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, null, OnTimeoutAction.FAIL, null);
        assertThat(req.prompt()).isNull();
    }

    @Test
    void timing_beforeExecution_outputEmptyByDefault() {
        ReviewRequest req = ReviewRequest.of("Task", null, ReviewTiming.BEFORE_EXECUTION, null);
        assertThat(req.timing()).isEqualTo(ReviewTiming.BEFORE_EXECUTION);
        assertThat(req.taskOutput()).isEmpty();
    }

    @Test
    void timing_duringExecution_returnsCorrectTiming() {
        ReviewRequest req = ReviewRequest.of("Agent question?", "", ReviewTiming.DURING_EXECUTION, null);
        assertThat(req.timing()).isEqualTo(ReviewTiming.DURING_EXECUTION);
    }

    // ========================
    // requiredRole
    // ========================

    @Test
    void of4arg_requiredRoleIsNull() {
        ReviewRequest req = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, null);
        assertThat(req.requiredRole()).isNull();
    }

    @Test
    void of6arg_requiredRoleIsNull() {
        ReviewRequest req = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, null, null, null);
        assertThat(req.requiredRole()).isNull();
    }

    @Test
    void of7arg_setsRequiredRole() {
        ReviewRequest req = ReviewRequest.of(
                "Open the safe",
                "",
                ReviewTiming.BEFORE_EXECUTION,
                Duration.ZERO,
                OnTimeoutAction.EXIT_EARLY,
                "Manager authorization required",
                "manager");

        assertThat(req.requiredRole()).isEqualTo("manager");
        assertThat(req.taskDescription()).isEqualTo("Open the safe");
        assertThat(req.prompt()).isEqualTo("Manager authorization required");
        assertThat(req.timeout()).isEqualTo(Duration.ZERO);
    }

    @Test
    void of7arg_nullRequiredRole_isNull() {
        ReviewRequest req = ReviewRequest.of("Task", "Out", ReviewTiming.AFTER_EXECUTION, null, null, null, null);
        assertThat(req.requiredRole()).isNull();
    }
}
