package net.agentensemble.review;

/**
 * A {@link ReviewHandler} that always returns {@link ReviewDecision#continueExecution()}
 * without any interaction or delay.
 *
 * <p>Use in CI pipelines and automated tests where review gates should be transparent
 * no-ops. Obtain an instance via {@link ReviewHandler#autoApprove()}.
 *
 * <p>This implementation is stateless and thread-safe. The singleton {@link #INSTANCE}
 * is shared across all callers.
 */
final class AutoApproveReviewHandler implements ReviewHandler {

    /** Singleton instance; use {@link ReviewHandler#autoApprove()} to obtain it. */
    static final AutoApproveReviewHandler INSTANCE = new AutoApproveReviewHandler();

    private AutoApproveReviewHandler() {}

    @Override
    public ReviewDecision review(ReviewRequest request) {
        return ReviewDecision.continueExecution();
    }
}
