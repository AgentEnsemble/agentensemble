package net.agentensemble.dashboard;

import java.time.Instant;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.review.ReviewHandler;

/**
 * SPI for an external execution dashboard that can be wired into an {@link net.agentensemble.Ensemble}
 * via {@code Ensemble.builder().webDashboard(dashboard)}.
 *
 * <p>Implements {@link AutoCloseable} so that dashboards managed with full lifecycle
 * control can use try-with-resources. The {@link #close()} method delegates to
 * {@link #stop()} and does not throw any checked exception.
 *
 * <p>Registering a dashboard with the ensemble builder auto-starts the server (if not
 * already running), attaches the streaming listener to the ensemble's listener chain, and
 * configures the review handler so that browser-based human-in-the-loop review gates work
 * without additional plumbing on the caller's side:
 *
 * <pre>
 * WebDashboard dashboard = WebDashboard.onPort(7329);
 *
 * EnsembleOutput output = Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .webDashboard(dashboard)
 *     .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
 *     .task(Task.of("Research AI trends"))
 *     .build()
 *     .run();
 * </pre>
 *
 * <p>Implementations are expected to be thread-safe. The streaming listener may be called
 * concurrently from multiple virtual threads in a parallel workflow.
 *
 * <p>This interface is intentionally lean; concrete implementations (e.g.
 * {@code net.agentensemble.web.WebDashboard}) carry the full configuration API
 * (port, host, review timeout, etc.).
 */
public interface EnsembleDashboard extends AutoCloseable {

    /**
     * Returns the {@link EnsembleListener} that translates execution lifecycle events into
     * wire-protocol messages and broadcasts them to connected dashboard clients.
     *
     * <p>This instance is created eagerly when the dashboard is built, so it is available
     * before {@link #start()} is called. The same instance is returned on every call.
     *
     * @return the streaming listener; never null
     */
    EnsembleListener streamingListener();

    /**
     * Returns the {@link ReviewHandler} that presents review gate requests to connected
     * browser clients and blocks until a decision arrives (or the review timeout expires).
     *
     * <p>This instance is created eagerly when the dashboard is built, so it is available
     * before {@link #start()} is called. The same instance is returned on every call.
     *
     * @return the web review handler; never null
     */
    ReviewHandler reviewHandler();

    /**
     * Starts the dashboard server. If the server is already running, this is a no-op
     * (idempotent).
     */
    void start();

    /**
     * Stops the dashboard server. If the server is not running, this is a no-op
     * (idempotent).
     */
    void stop();

    /**
     * Stops the dashboard server. Equivalent to {@link #stop()}, provided so that
     * dashboards can be used in try-with-resources blocks:
     *
     * <pre>
     * try (WebDashboard dashboard = WebDashboard.onPort(7329)) {
     *     dashboard.start();
     *     Ensemble.builder()
     *         .listener(dashboard.streamingListener())
     *         .task(...)
     *         .build()
     *         .run();
     * }  // dashboard.close() called automatically -- server stops here
     * </pre>
     *
     * <p>Default implementation delegates to {@link #stop()}. Does not throw any
     * checked exception.
     */
    @Override
    default void close() {
        stop();
    }

    /**
     * Returns {@code true} if the dashboard server is currently running and accepting
     * WebSocket connections.
     *
     * @return true when running
     */
    boolean isRunning();

    /**
     * Called by the {@link net.agentensemble.Ensemble} executor before the first task
     * begins, after validation and agent synthesis are complete. Implementations can
     * broadcast an {@code ensemble_started} message to connected dashboard clients.
     *
     * <p>Default implementation is a no-op.
     *
     * @param ensembleId a UUID identifying this run
     * @param startedAt  the instant this run began
     * @param totalTasks total number of tasks in this run
     * @param workflow   the workflow strategy in use (e.g. {@code "SEQUENTIAL"})
     */
    default void onEnsembleStarted(String ensembleId, Instant startedAt, int totalTasks, String workflow) {}

    /**
     * Called by the {@link net.agentensemble.Ensemble} executor after the run has
     * completed (either normally or via an early exit). Implementations can broadcast
     * an {@code ensemble_completed} message to connected dashboard clients.
     *
     * <p>Default implementation is a no-op.
     *
     * @param ensembleId     the UUID identifying this run
     * @param completedAt    the instant this run completed
     * @param durationMs     total elapsed time in milliseconds
     * @param exitReason     the reason the run ended (e.g. {@code "COMPLETED"})
     * @param totalTokens    total token count across all tasks ({@code -1} if unknown)
     * @param totalToolCalls total number of tool invocations across all tasks
     */
    default void onEnsembleCompleted(
            String ensembleId,
            Instant completedAt,
            long durationMs,
            String exitReason,
            long totalTokens,
            int totalToolCalls) {}
}
