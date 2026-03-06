package net.agentensemble.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.dashboard.EnsembleDashboard;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.web.protocol.EnsembleCompletedMessage;
import net.agentensemble.web.protocol.EnsembleStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live execution dashboard: an embedded WebSocket server that streams ensemble lifecycle
 * events to connected browser clients in real time.
 *
 * <p>Implements {@link EnsembleDashboard} so it integrates with the ensemble builder in a
 * single call:
 *
 * <pre>
 * WebDashboard dashboard = WebDashboard.onPort(7329);
 *
 * EnsembleOutput output = Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .webDashboard(dashboard)               // auto-starts; wires listener + review handler
 *     .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
 *     .task(Task.of("Research AI trends"))
 *     .build()
 *     .run();
 * </pre>
 *
 * <p>Or, for explicit lifecycle control:
 *
 * <pre>
 * WebDashboard dashboard = WebDashboard.builder()
 *     .port(7329)
 *     .host("localhost")
 *     .reviewTimeout(Duration.ofMinutes(10))
 *     .onTimeout(OnTimeoutAction.CONTINUE)
 *     .build();
 *
 * dashboard.start();
 * // Open http://localhost:7329 in a browser...
 *
 * Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .listener(dashboard.streamingListener())
 *     .reviewHandler(dashboard.reviewHandler())
 *     .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
 *     .task(...)
 *     .build()
 *     .run();
 *
 * dashboard.stop();
 * </pre>
 *
 * <h2>Port</h2>
 * <p>Valid ports are 0&ndash;65535. Port 0 instructs the OS to assign an ephemeral port,
 * which is useful in tests; call {@code actualPort()} after {@link #start()} to discover it.
 *
 * <h2>Review gates</h2>
 * <p>When registered via {@code Ensemble.builder().webDashboard(dashboard)}, the
 * {@link WebReviewHandler} returned by {@link #reviewHandler()} replaces the ensemble's
 * review handler. The handler broadcasts a {@code review_requested} message to all
 * connected clients and blocks until:
 * <ul>
 *   <li>A browser sends a {@code review_decision} message.</li>
 *   <li>The configured {@code reviewTimeout} expires.</li>
 *   <li>All clients disconnect.</li>
 * </ul>
 *
 * <h2>Origin security</h2>
 * <p>When bound to {@code localhost} (the default), only connections from localhost origins
 * are accepted. See {@link WebSocketServer#isOriginAllowed} for the full policy.
 */
public final class WebDashboard implements EnsembleDashboard {

    private static final Logger log = LoggerFactory.getLogger(WebDashboard.class);

    // ========================
    // Configured fields
    // ========================

    private final int port;
    private final String host;
    private final Duration reviewTimeout;
    private final OnTimeoutAction onTimeout;

    // ========================
    // Internal infrastructure -- created eagerly so listener/handler are available before start()
    // ========================

    private final MessageSerializer serializer;
    private final ConnectionManager connectionManager;
    private final ScheduledExecutorService heartbeatScheduler;
    private final WebSocketServer server;
    private final WebSocketStreamingListener streamingListener;
    private final WebReviewHandler reviewHandler;

    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);

    private WebDashboard(Builder builder) {
        this.port = builder.port;
        this.host = builder.host;
        this.reviewTimeout = builder.reviewTimeout;
        this.onTimeout = builder.onTimeout;

        this.serializer = new MessageSerializer();
        this.connectionManager = new ConnectionManager(serializer);
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentensemble-web-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.server = new WebSocketServer(connectionManager, serializer, heartbeatScheduler);
        this.streamingListener = new WebSocketStreamingListener(connectionManager, serializer);
        this.reviewHandler = new WebReviewHandler(connectionManager, serializer, reviewTimeout, onTimeout);
    }

    // ========================
    // Static factory methods
    // ========================

    /**
     * Create a dashboard on the given port using all other defaults:
     * host {@code localhost}, review timeout 5 minutes, on-timeout action {@code CONTINUE}.
     *
     * <p>Port 0 is valid and causes the OS to assign an ephemeral port (useful in tests).
     *
     * @param port the TCP port to listen on; must be 0&ndash;65535
     * @return a new, not-yet-started WebDashboard
     * @throws IllegalArgumentException when {@code port} is outside the valid range
     */
    public static WebDashboard onPort(int port) {
        return builder().port(port).build();
    }

    /**
     * Returns a new builder for constructing a {@link WebDashboard} with custom settings.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ========================
    // EnsembleDashboard lifecycle
    // ========================

    /**
     * Starts the embedded WebSocket server. If the server is already running, this is a
     * no-op (idempotent).
     *
     * <p>After start, the server is accessible at {@code ws://{host}:{actualPort()}/ws}
     * and a JVM shutdown hook is registered (once) to stop the server cleanly on exit.
     */
    @Override
    public void start() {
        if (server.isRunning()) {
            return;
        }

        server.start(port, host);

        // Route incoming ReviewDecisionMessage messages to the ConnectionManager so
        // that pending review futures are resolved.
        server.setClientMessageHandler(msg -> {
            if (msg instanceof ReviewDecisionMessage rdm) {
                connectionManager.resolveReview(rdm.reviewId(), serializer.toJson(rdm));
            }
        });

        // Register a JVM shutdown hook the first time start() completes successfully.
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "agentensemble-web-shutdown"));
        }
    }

    /**
     * Stops the embedded WebSocket server. If the server is not running, this is a no-op
     * (idempotent).
     */
    @Override
    public void stop() {
        server.stop();
    }

    /**
     * Returns {@code true} if the embedded server is currently running and accepting
     * WebSocket connections.
     */
    @Override
    public boolean isRunning() {
        return server.isRunning();
    }

    // ========================
    // EnsembleDashboard lifecycle hooks
    // ========================

    /**
     * {@inheritDoc}
     *
     * <p>Broadcasts an {@code ensemble_started} message to all connected clients and
     * notifies the {@link ConnectionManager} to clear any stale snapshot from a
     * previous run and record the new ensemble ID and start time.
     */
    @Override
    public void onEnsembleStarted(String ensembleId, Instant startedAt, int totalTasks, String workflow) {
        connectionManager.noteEnsembleStarted(ensembleId, startedAt);
        try {
            EnsembleStartedMessage msg = new EnsembleStartedMessage(ensembleId, startedAt, totalTasks, workflow);
            String json = serializer.toJson(msg);
            connectionManager.broadcast(json);
            connectionManager.appendToSnapshot(json);
        } catch (Exception e) {
            log.warn("Failed to broadcast ensemble_started message: {}", e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Broadcasts an {@code ensemble_completed} message to all connected clients.
     */
    @Override
    public void onEnsembleCompleted(
            String ensembleId,
            Instant completedAt,
            long durationMs,
            String exitReason,
            long totalTokens,
            int totalToolCalls) {
        try {
            EnsembleCompletedMessage msg = new EnsembleCompletedMessage(
                    ensembleId, completedAt, durationMs, exitReason, totalTokens, totalToolCalls);
            String json = serializer.toJson(msg);
            connectionManager.broadcast(json);
            connectionManager.appendToSnapshot(json);
        } catch (Exception e) {
            log.warn("Failed to broadcast ensemble_completed message: {}", e.getMessage(), e);
        }
    }

    // ========================
    // EnsembleDashboard component accessors
    // ========================

    /**
     * Returns the {@link EnsembleListener} that translates execution lifecycle events into
     * WebSocket protocol messages broadcast to all connected clients.
     *
     * <p>The same instance is returned on every call. This listener is created at build
     * time and is functional before {@link #start()} is called (events sent while no
     * clients are connected are silently dropped).
     *
     * @return the streaming listener; never null
     */
    @Override
    public EnsembleListener streamingListener() {
        return streamingListener;
    }

    /**
     * Returns the {@link ReviewHandler} that presents review gate requests to browser
     * clients and blocks the calling thread until a decision arrives.
     *
     * <p>The same instance is returned on every call.
     *
     * @return the web review handler; never null
     */
    @Override
    public ReviewHandler reviewHandler() {
        return reviewHandler;
    }

    // ========================
    // Configuration accessors (primarily for testing)
    // ========================

    /**
     * Returns the port this dashboard was configured with.
     *
     * <p>If port 0 was configured (ephemeral), this returns 0 even after start.
     * Use {@link #actualPort()} to obtain the port the OS assigned.
     *
     * @return the configured port (0&ndash;65535)
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the host/interface binding this dashboard was configured with.
     *
     * @return the configured host; never null
     */
    public String getHost() {
        return host;
    }

    /**
     * Returns the review timeout configured on this dashboard.
     *
     * @return the review timeout; never null
     */
    public Duration getReviewTimeout() {
        return reviewTimeout;
    }

    /**
     * Returns the on-timeout action configured on this dashboard.
     *
     * @return the on-timeout action; never null
     */
    public OnTimeoutAction getOnTimeout() {
        return onTimeout;
    }

    /**
     * Returns the port the server is actually listening on after {@link #start()} has been
     * called.
     *
     * <p>For explicit ports this is the same as {@link #getPort()}. For ephemeral port 0,
     * this returns the OS-assigned port number. Returns -1 when the server is not running.
     *
     * @return the actual listening port, or -1 if not running
     */
    public int actualPort() {
        return server.port();
    }

    // ========================
    // Builder
    // ========================

    /**
     * Fluent builder for {@link WebDashboard}.
     *
     * <p>Defaults:
     * <ul>
     *   <li>host: {@code localhost}</li>
     *   <li>reviewTimeout: 5 minutes</li>
     *   <li>onTimeout: {@link OnTimeoutAction#CONTINUE}</li>
     * </ul>
     *
     * <p>Port is required (no default); use {@link WebDashboard#onPort(int)} for a
     * single-expression shorthand.
     */
    public static final class Builder {

        private int port = -1;
        private String host = "localhost";
        private Duration reviewTimeout = Duration.ofMinutes(5);
        private OnTimeoutAction onTimeout = OnTimeoutAction.CONTINUE;

        private Builder() {}

        /**
         * Sets the TCP port to listen on.
         *
         * <p>Valid range: 0&ndash;65535. Port 0 causes the OS to assign an ephemeral port.
         *
         * @param port the port; must be 0&ndash;65535
         * @return this builder
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Sets the host/interface to bind to.
         *
         * <p>Use {@code localhost} (default) to restrict connections to the local machine.
         * Use {@code 0.0.0.0} to accept connections on all interfaces (exposes the dashboard
         * to the network; secure appropriately).
         *
         * @param host the hostname or IP address; must not be null or blank
         * @return this builder
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Sets how long to wait for a browser decision on a review gate before the
         * {@link #onTimeout(OnTimeoutAction)} action is applied.
         *
         * @param reviewTimeout the timeout duration; must not be null or negative
         * @return this builder
         */
        public Builder reviewTimeout(Duration reviewTimeout) {
            this.reviewTimeout = reviewTimeout;
            return this;
        }

        /**
         * Sets the action to take when a review gate times out with no browser decision.
         *
         * @param onTimeout the on-timeout action; must not be null
         * @return this builder
         */
        public Builder onTimeout(OnTimeoutAction onTimeout) {
            this.onTimeout = onTimeout;
            return this;
        }

        /**
         * Validates configuration and constructs the {@link WebDashboard}.
         *
         * <p>The returned dashboard is not yet started; call {@link WebDashboard#start()}
         * (or let {@code Ensemble.builder().webDashboard(dashboard)} start it automatically).
         *
         * @return a new, not-yet-started WebDashboard
         * @throws IllegalArgumentException when any field fails validation
         */
        public WebDashboard build() {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException(
                        "port must be in the range 0-65535 (0 = OS-assigned ephemeral port); got: " + port);
            }
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("host must not be null or blank; got: " + host);
            }
            if (reviewTimeout == null) {
                throw new IllegalArgumentException("reviewTimeout must not be null");
            }
            if (reviewTimeout.isNegative()) {
                throw new IllegalArgumentException("reviewTimeout must not be negative; got: " + reviewTimeout);
            }
            Objects.requireNonNull(onTimeout, "onTimeout must not be null");
            return new WebDashboard(this);
        }
    }
}
