package net.agentensemble.dashboard;

import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.review.ReviewHandler;

/**
 * SPI for an external execution dashboard that can be wired into an {@link net.agentensemble.Ensemble}
 * via {@code Ensemble.builder().webDashboard(dashboard)}.
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
public interface EnsembleDashboard {

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
     * Returns {@code true} if the dashboard server is currently running and accepting
     * WebSocket connections.
     *
     * @return true when running
     */
    boolean isRunning();
}
