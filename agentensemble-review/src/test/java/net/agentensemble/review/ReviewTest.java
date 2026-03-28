package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Review} factory methods and builder.
 */
class ReviewTest {

    // ========================
    // Static factories
    // ========================

    @Test
    void required_isNotSkipAndIsRequired() {
        Review review = Review.required();
        assertThat(review.isSkip()).isFalse();
        assertThat(review.isRequired()).isTrue();
        assertThat(review.getPrompt()).isNull();
        assertThat(review.getTimeout()).isEqualTo(Review.DEFAULT_TIMEOUT);
        assertThat(review.getOnTimeoutAction()).isEqualTo(Review.DEFAULT_ON_TIMEOUT);
    }

    @Test
    void required_withPrompt_setsPrompt() {
        Review review = Review.required("Please approve this output");
        assertThat(review.isRequired()).isTrue();
        assertThat(review.getPrompt()).isEqualTo("Please approve this output");
    }

    @Test
    void required_withNullPrompt_throwsIllegalArgument() {
        assertThatThrownBy(() -> Review.required(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skip_isSkipAndNotRequired() {
        Review review = Review.skip();
        assertThat(review.isSkip()).isTrue();
        assertThat(review.isRequired()).isFalse();
        assertThat(review.getTimeout()).isNull();
        assertThat(review.getOnTimeoutAction()).isNull();
    }

    // ========================
    // Builder
    // ========================

    @Test
    void builder_defaultsAreApplied() {
        Review review = Review.builder().build();
        assertThat(review.isRequired()).isTrue();
        assertThat(review.getTimeout()).isEqualTo(Review.DEFAULT_TIMEOUT);
        assertThat(review.getOnTimeoutAction()).isEqualTo(OnTimeoutAction.EXIT_EARLY);
        assertThat(review.getPrompt()).isNull();
    }

    @Test
    void builder_customTimeout() {
        Duration customTimeout = Duration.ofMinutes(10);
        Review review = Review.builder().timeout(customTimeout).build();
        assertThat(review.getTimeout()).isEqualTo(customTimeout);
    }

    @Test
    void builder_customOnTimeoutAction() {
        Review review = Review.builder().onTimeout(OnTimeoutAction.CONTINUE).build();
        assertThat(review.getOnTimeoutAction()).isEqualTo(OnTimeoutAction.CONTINUE);
    }

    @Test
    void builder_customPrompt() {
        Review review = Review.builder().prompt("Check this carefully").build();
        assertThat(review.getPrompt()).isEqualTo("Check this carefully");
    }

    @Test
    void builder_allFields() {
        Review review = Review.builder()
                .timeout(Duration.ofSeconds(30))
                .onTimeout(OnTimeoutAction.FAIL)
                .prompt("Emergency review")
                .build();
        assertThat(review.getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(review.getOnTimeoutAction()).isEqualTo(OnTimeoutAction.FAIL);
        assertThat(review.getPrompt()).isEqualTo("Emergency review");
        assertThat(review.isRequired()).isTrue();
    }

    @Test
    void builder_nullTimeout_throwsIllegalArgument() {
        assertThatThrownBy(() -> Review.builder().timeout(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_zeroTimeout_meansWaitIndefinitely() {
        Review review = Review.builder().timeout(Duration.ZERO).build();
        assertThat(review.getTimeout()).isEqualTo(Duration.ZERO);
    }

    @Test
    void builder_negativeTimeout_throwsIllegalArgument() {
        assertThatThrownBy(() -> Review.builder().timeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_nullOnTimeout_throwsIllegalArgument() {
        assertThatThrownBy(() -> Review.builder().onTimeout(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_nullPrompt_throwsIllegalArgument() {
        assertThatThrownBy(() -> Review.builder().prompt(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // requiredRole
    // ========================

    @Test
    void required_noRequiredRole() {
        Review review = Review.required();
        assertThat(review.getRequiredRole()).isNull();
    }

    @Test
    void builder_requiredRole_setsRole() {
        Review review = Review.builder().requiredRole("manager").build();
        assertThat(review.getRequiredRole()).isEqualTo("manager");
        assertThat(review.isRequired()).isTrue();
    }

    @Test
    void builder_requiredRole_nullThrows() {
        assertThatThrownBy(() -> Review.builder().requiredRole(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_requiredRole_blankThrows() {
        assertThatThrownBy(() -> Review.builder().requiredRole("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_requiredRole_withZeroTimeout() {
        Review review = Review.builder()
                .requiredRole("manager")
                .prompt("Manager authorization required")
                .timeout(Duration.ZERO)
                .build();
        assertThat(review.getRequiredRole()).isEqualTo("manager");
        assertThat(review.getTimeout()).isEqualTo(Duration.ZERO);
        assertThat(review.getPrompt()).isEqualTo("Manager authorization required");
    }

    @Test
    void skip_noRequiredRole() {
        Review review = Review.skip();
        assertThat(review.getRequiredRole()).isNull();
    }
}
