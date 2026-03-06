package net.agentensemble.review;

import java.time.Duration;

/**
 * SPI for human-in-the-loop review gates.
 *
 * <p>A {@code ReviewHandler} receives a {@link ReviewRequest} from the execution engine
 * and returns a {@link ReviewDecision} that controls what happens next. Implementations
 * may block on stdin, call a remote webhook, auto-approve, or use any other mechanism.
 *
 * <p>Register the handler on the ensemble:
 * <pre>
 * Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .reviewHandler(ReviewHandler.console())
 *     .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
 *     .task(Task.of("Research AI trends"))
 *     .build()
 *     .run();
 * </pre>
 *
 * <p>Built-in implementations are available via static factories on this interface:
 * <ul>
 *   <li>{@link #console()} -- CLI implementation; blocks on stdin; displays countdown timer</li>
 *   <li>{@link #autoApprove()} -- always returns {@link ReviewDecision.Continue}; for CI/tests</li>
 *   <li>{@link #autoApproveWithDelay(Duration)} -- returns Continue after delay; simulates human timing</li>
 *   <li>Browser-based review -- use {@code WebDashboard.reviewHandler()} from the
 *       {@code agentensemble-web} module (v2.1.0+)</li>
 * </ul>
 *
 * <p>Custom implementations may be provided by implementing this functional interface.
 *
 * <p>Thread safety: implementations must be thread-safe when used with parallel workflows,
 * where multiple tasks may request review concurrently.
 */
@FunctionalInterface
public interface ReviewHandler {

    /**
     * Review the given request and return a decision.
     *
     * <p>This method may block indefinitely (e.g., waiting for stdin input). Implementations
     * should respect the {@link ReviewRequest#timeout()} and {@link ReviewRequest#onTimeoutAction()}
     * to bound the wait time.
     *
     * @param request the review context; never null
     * @return the review decision; must not be null
     */
    ReviewDecision review(ReviewRequest request);

    // ========================
    // Static factory methods
    // ========================

    /**
     * Return a {@link ConsoleReviewHandler} that blocks on stdin, displays a countdown timer,
     * and accepts Continue / Edit / ExitEarly input.
     *
     * <p>Uses {@code System.in} and {@code System.out} for I/O. Suitable for interactive
     * CLI applications.
     *
     * @return a new ConsoleReviewHandler
     */
    static ReviewHandler console() {
        return new ConsoleReviewHandler();
    }

    /**
     * Return a {@link ReviewHandler} that always returns {@link ReviewDecision#continueExecution()}.
     *
     * <p>Use in CI pipelines and automated tests where review gates should be transparent
     * no-ops. All tasks execute without pausing.
     *
     * @return an auto-approving ReviewHandler
     */
    static ReviewHandler autoApprove() {
        return AutoApproveReviewHandler.INSTANCE;
    }

    /**
     * Return a {@link ReviewHandler} that returns {@link ReviewDecision#continueExecution()}
     * after sleeping for the given delay.
     *
     * <p>Use in tests that need to simulate realistic human review timing without
     * blocking on stdin. The delay does not respect the request timeout.
     *
     * @param delay how long to sleep before returning Continue; must not be null or negative
     * @return an auto-approving ReviewHandler with a simulated delay
     */
    static ReviewHandler autoApproveWithDelay(Duration delay) {
        if (delay == null) {
            throw new IllegalArgumentException("delay must not be null");
        }
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return new AutoApproveWithDelayReviewHandler(delay);
    }
}
