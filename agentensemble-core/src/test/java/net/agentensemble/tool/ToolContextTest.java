package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.Executors;
import net.agentensemble.review.ReviewHandler;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolContext: factory methods, accessors, and reviewHandler field.
 */
class ToolContextTest {

    private static final ToolMetrics NOOP = NoOpToolMetrics.INSTANCE;

    // ========================
    // Three-argument factory -- no reviewHandler
    // ========================

    @Test
    void of_threeArg_reviewHandlerIsNull() {
        var ctx = ToolContext.of("tool", NOOP, Executors.newVirtualThreadPerTaskExecutor());
        assertThat(ctx.reviewHandler()).isNull();
    }

    @Test
    void of_threeArg_delegatesToFourArg() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var ctx = ToolContext.of("tool", NOOP, executor);
        assertThat(ctx.metrics()).isSameAs(NOOP);
        assertThat(ctx.executor()).isSameAs(executor);
    }

    // ========================
    // Four-argument factory -- with reviewHandler
    // ========================

    @Test
    void of_fourArg_withNullReviewHandler_reviewHandlerIsNull() {
        var ctx = ToolContext.of("tool", NOOP, Executors.newVirtualThreadPerTaskExecutor(), null);
        assertThat(ctx.reviewHandler()).isNull();
    }

    @Test
    void of_fourArg_withReviewHandler_returnsHandler() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        var ctx = ToolContext.of("tool", NOOP, Executors.newVirtualThreadPerTaskExecutor(), handler);
        assertThat(ctx.reviewHandler()).isSameAs(handler);
    }

    @Test
    void of_fourArg_storesHandlerAsObject_canCastToReviewHandler() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        var ctx = ToolContext.of("tool", NOOP, Executors.newVirtualThreadPerTaskExecutor(), handler);
        Object stored = ctx.reviewHandler();
        assertThat(stored).isInstanceOf(ReviewHandler.class);
        assertThat((ReviewHandler) stored).isSameAs(handler);
    }

    // ========================
    // Null argument validation
    // ========================

    @Test
    void of_nullToolName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolContext.of(null, NOOP, Executors.newVirtualThreadPerTaskExecutor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    @Test
    void of_nullMetrics_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolContext.of("tool", null, Executors.newVirtualThreadPerTaskExecutor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metrics");
    }

    @Test
    void of_nullExecutor_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ToolContext.of("tool", NOOP, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executor");
    }

    // ========================
    // Accessor correctness
    // ========================

    @Test
    void logger_scopedToToolName() {
        var ctx = ToolContext.of("my_tool", NOOP, Executors.newVirtualThreadPerTaskExecutor());
        assertThat(ctx.logger().getName()).isEqualTo("net.agentensemble.tool.my_tool");
    }

    @Test
    void metrics_returnsInjectedMetrics() {
        var ctx = ToolContext.of("tool", NOOP, Executors.newVirtualThreadPerTaskExecutor());
        assertThat(ctx.metrics()).isSameAs(NOOP);
    }

    @Test
    void executor_returnsInjectedExecutor() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var ctx = ToolContext.of("tool", NOOP, executor);
        assertThat(ctx.executor()).isSameAs(executor);
    }
}
