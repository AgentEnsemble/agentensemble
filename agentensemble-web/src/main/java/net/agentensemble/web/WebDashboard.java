package net.agentensemble.web;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.agentensemble.Ensemble;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.dashboard.EnsembleDashboard;
import net.agentensemble.dashboard.RequestContext;
import net.agentensemble.dashboard.RequestHandler;
import net.agentensemble.directive.Directive;
import net.agentensemble.directive.DirectiveStore;
import net.agentensemble.ensemble.EnsembleLifecycleState;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.trace.export.ExecutionTraceExporter;
import net.agentensemble.trace.export.JsonTraceExporter;
import net.agentensemble.web.protocol.DirectiveAckMessage;
import net.agentensemble.web.protocol.DirectiveActiveMessage;
import net.agentensemble.web.protocol.DirectiveMessage;
import net.agentensemble.web.protocol.EnsembleCompletedMessage;
import net.agentensemble.web.protocol.EnsembleStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import net.agentensemble.web.protocol.TaskAcceptedMessage;
import net.agentensemble.web.protocol.TaskRequestMessage;
import net.agentensemble.web.protocol.TaskResponseMessage;
import net.agentensemble.web.protocol.ToolRequestMessage;
import net.agentensemble.web.protocol.ToolResponseMessage;
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

    /**
     * Optional directory to which each run's trace is exported as
     * {@code {ensembleId}.json}. Null when no automatic export is desired.
     */
    private final Path traceExportDir;

    /**
     * Maximum number of completed runs retained in the late-join snapshot.
     * When a new run starts and the total exceeds this cap, the oldest run's events
     * are evicted from the snapshot. Default is 10.
     */
    private final int maxRetainedRuns;

    /**
     * Optional workspace root path for file browsing endpoints. Null when not configured.
     */
    private final Path workspacePath;

    /**
     * Eagerly created trace exporter when {@link #traceExportDir} is configured.
     * Null otherwise.
     */
    private final ExecutionTraceExporter configuredTraceExporter;

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

    /** Request handler for incoming cross-ensemble work requests. Set via setRequestHandler(). */
    private volatile RequestHandler requestHandler;

    /** Directive store for human-injected directives. Set via setDirectiveStore(). */
    private volatile DirectiveStore directiveStore;

    /** Ensemble reference for dispatching control-plane directives. Set via setEnsemble(). */
    private volatile Ensemble ensemble;

    /** Virtual-thread executor for processing incoming work requests asynchronously. */
    private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private WebDashboard(Builder builder) {
        this.port = builder.port;
        this.host = builder.host;
        this.reviewTimeout = builder.reviewTimeout;
        this.onTimeout = builder.onTimeout;
        this.traceExportDir = builder.traceExportDir;
        this.maxRetainedRuns = builder.maxRetainedRuns;
        this.workspacePath = builder.workspacePath;
        this.configuredTraceExporter = traceExportDir != null ? new JsonTraceExporter(traceExportDir) : null;

        this.serializer = new MessageSerializer();
        this.connectionManager = new ConnectionManager(serializer, maxRetainedRuns);
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentensemble-web-heartbeat");
            t.setDaemon(true);
            return t;
        });
        this.server = new WebSocketServer(connectionManager, serializer, heartbeatScheduler);
        if (workspacePath != null) {
            this.server.setWorkspacePath(workspacePath);
        }
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

        // Route incoming client messages to the appropriate handler.
        // Use the session-aware handler to enable targeted responses for task/tool requests.
        server.setSessionAwareClientMessageHandler((sessionId, msg) -> {
            if (msg instanceof ReviewDecisionMessage rdm) {
                connectionManager.resolveReview(rdm.reviewId(), serializer.toJson(rdm));
            } else if (msg instanceof TaskRequestMessage trm) {
                handleTaskRequest(sessionId, trm);
            } else if (msg instanceof ToolRequestMessage trm) {
                handleToolRequest(sessionId, trm);
            } else if (msg instanceof DirectiveMessage dm) {
                handleDirective(sessionId, dm);
            }
        });

        // Register a JVM shutdown hook the first time start() completes successfully.
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "agentensemble-web-shutdown"));
        }
    }

    /**
     * Stops the embedded WebSocket server and releases the internal heartbeat scheduler.
     * If the server is not running, this is a no-op (idempotent).
     *
     * <p>When the server was running, {@code stop()} shuts down the heartbeat
     * {@code ScheduledExecutorService} and awaits termination of its worker thread (up to
     * 2 seconds) to prevent resource leaks in long-running processes that create and stop
     * multiple dashboards. The heartbeat thread is a daemon thread and therefore does not
     * block JVM exit on its own, but releasing the executor ensures clean lifecycle
     * semantics.
     *
     * <p><strong>Note:</strong> Once stopped, this {@code WebDashboard} instance should not
     * be restarted. The scheduler is terminated on the first {@code stop()} call when the
     * server was running; a subsequent {@code start()} would attempt to reschedule the
     * heartbeat on a terminated executor.
     */
    @Override
    public void stop() {
        boolean wasRunning = server.isRunning();
        server.stop();
        if (wasRunning) {
            heartbeatScheduler.shutdownNow();
            requestExecutor.shutdownNow();
            try {
                if (!heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("Heartbeat scheduler did not terminate within 2 seconds");
                }
                if (!requestExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    log.warn("Request executor did not terminate within 2 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
            if (log.isWarnEnabled()) {
                log.warn("Failed to broadcast ensemble_started message: {}", e.getMessage(), e);
            }
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
            if (log.isWarnEnabled()) {
                log.warn("Failed to broadcast ensemble_completed message: {}", e.getMessage(), e);
            }
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
     * Returns the trace export directory configured on this dashboard, or {@code null} when
     * no automatic trace export was configured.
     *
     * @return the trace export directory, or {@code null}
     */
    public Path getTraceExportDir() {
        return traceExportDir;
    }

    /**
     * Returns the maximum number of completed runs retained in the late-join snapshot.
     *
     * @return the configured cap; always &ge; 1
     */
    public int getMaxRetainedRuns() {
        return maxRetainedRuns;
    }

    /**
     * Returns an {@link ExecutionTraceExporter} that writes each run's trace to
     * {@link #getTraceExportDir()}, or {@code null} when no trace export directory was
     * configured.
     *
     * <p>When non-null, {@link net.agentensemble.Ensemble.EnsembleBuilder#webDashboard}
     * automatically wires this exporter so that callers do not need a separate
     * {@code .traceExporter(...)} call on the ensemble builder.
     *
     * @return the configured trace exporter, or {@code null}
     */
    @Override
    public ExecutionTraceExporter traceExporter() {
        return configuredTraceExporter;
    }

    // ========================
    // Request handler (EN-007: Incoming work request handler)
    // ========================

    /**
     * {@inheritDoc}
     *
     * <p>Stores the request handler so that incoming {@link TaskRequestMessage} and
     * {@link ToolRequestMessage} messages can be dispatched to the ensemble's shared
     * tasks and tools.
     */
    @Override
    public void setRequestHandler(RequestHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        this.requestHandler = handler;
    }

    private void handleTaskRequest(String sessionId, TaskRequestMessage msg) {
        RequestHandler handler = this.requestHandler;
        if (handler == null) {
            log.warn("Received task_request but no RequestHandler is configured");
            return;
        }

        // Execute asynchronously on virtual thread
        requestExecutor.submit(() -> {
            try {
                // Create RequestContext from message fields
                RequestContext ctx = new RequestContext(
                        msg.requestId(),
                        msg.cacheKey(),
                        msg.cachePolicy() != null ? msg.cachePolicy().name() : null,
                        msg.maxAge());

                RequestHandler.TaskResult result = handler.handleTaskRequest(msg.task(), msg.context(), ctx);

                // Only send task_accepted if the request was actually accepted (not rejected)
                if (!"REJECTED".equals(result.status())) {
                    try {
                        TaskAcceptedMessage accepted = new TaskAcceptedMessage(msg.requestId(), 0, null);
                        connectionManager.send(sessionId, serializer.toJson(accepted));
                    } catch (Exception e) {
                        log.warn("Failed to send task_accepted for requestId {}: {}", msg.requestId(), e.getMessage());
                    }
                }

                TaskResponseMessage response = new TaskResponseMessage(
                        msg.requestId(), result.status(), result.result(), result.error(), result.durationMs());
                connectionManager.send(sessionId, serializer.toJson(response));
            } catch (Exception e) {
                log.warn("Failed to handle task_request for '{}': {}", msg.task(), e.getMessage(), e);
                try {
                    TaskResponseMessage errorResponse =
                            new TaskResponseMessage(msg.requestId(), "FAILED", null, e.getMessage(), null);
                    connectionManager.send(sessionId, serializer.toJson(errorResponse));
                } catch (Exception ex) {
                    log.warn("Failed to send error response: {}", ex.getMessage());
                }
            }
        });
    }

    private void handleToolRequest(String sessionId, ToolRequestMessage msg) {
        RequestHandler handler = this.requestHandler;
        if (handler == null) {
            log.warn("Received tool_request but no RequestHandler is configured");
            return;
        }

        // Execute asynchronously on virtual thread
        requestExecutor.submit(() -> {
            try {
                RequestHandler.ToolResult result = handler.handleToolRequest(msg.tool(), msg.input());
                ToolResponseMessage response = new ToolResponseMessage(
                        msg.requestId(), result.status(), result.result(), result.error(), result.durationMs());
                connectionManager.send(sessionId, serializer.toJson(response));
            } catch (Exception e) {
                log.warn("Failed to handle tool_request for '{}': {}", msg.tool(), e.getMessage(), e);
                try {
                    ToolResponseMessage errorResponse =
                            new ToolResponseMessage(msg.requestId(), "FAILED", null, e.getMessage(), null);
                    connectionManager.send(sessionId, serializer.toJson(errorResponse));
                } catch (Exception ex) {
                    log.warn("Failed to send error response: {}", ex.getMessage());
                }
            }
        });
    }

    // ========================
    // Directive handling (EN-020: Human directives)
    // ========================

    /**
     * Sets the directive store so that incoming {@link DirectiveMessage} messages can
     * be stored and broadcast to connected clients.
     *
     * @param store the directive store; must not be null
     */
    @Override
    public void setDirectiveStore(DirectiveStore store) {
        Objects.requireNonNull(store, "store must not be null");
        this.directiveStore = store;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stores the ensemble reference so that incoming control-plane directives
     * can be dispatched through the ensemble's {@link net.agentensemble.directive.DirectiveDispatcher}.
     */
    @Override
    public void setEnsemble(Ensemble ensemble) {
        Objects.requireNonNull(ensemble, "ensemble must not be null");
        this.ensemble = ensemble;
    }

    private void handleDirective(String sessionId, DirectiveMessage dm) {
        DirectiveStore store = this.directiveStore;
        if (store == null) {
            log.warn("Received directive but no DirectiveStore is configured");
            return;
        }

        try {
            // Validate: context directives (action == null) must have non-blank content
            if (dm.action() == null && (dm.content() == null || dm.content().isBlank())) {
                DirectiveAckMessage ack = new DirectiveAckMessage(null, "REJECTED");
                connectionManager.send(sessionId, serializer.toJson(ack));
                return;
            }

            // Validate: control-plane directives (action != null) must have non-blank action
            if (dm.action() != null && dm.action().isBlank()) {
                DirectiveAckMessage ack = new DirectiveAckMessage(null, "REJECTED");
                connectionManager.send(sessionId, serializer.toJson(ack));
                return;
            }

            // Parse TTL to compute expiry
            Instant now = Instant.now();
            Instant expiresAt = null;
            if (dm.ttl() != null && !dm.ttl().isBlank()) {
                Duration ttl = Duration.parse(dm.ttl());
                // Validate: TTL must be positive (not negative)
                if (ttl.isNegative()) {
                    DirectiveAckMessage ack = new DirectiveAckMessage(null, "REJECTED");
                    connectionManager.send(sessionId, serializer.toJson(ack));
                    return;
                }
                expiresAt = now.plus(ttl);
            }

            // Create and store the directive
            String directiveId = java.util.UUID.randomUUID().toString();
            Directive directive =
                    new Directive(directiveId, dm.from(), dm.content(), dm.action(), dm.value(), now, expiresAt);
            store.add(directive);

            // Dispatch control-plane directives through the ensemble's DirectiveDispatcher
            if (dm.action() != null) {
                Ensemble ens = this.ensemble;
                if (ens != null) {
                    ens.getDirectiveDispatcher().dispatch(directive, ens);
                } else {
                    log.warn(
                            "Control-plane directive '{}' stored but no Ensemble is configured for dispatch",
                            dm.action());
                }
            }

            // Broadcast to all clients
            DirectiveActiveMessage activeMsg = new DirectiveActiveMessage(
                    directiveId,
                    dm.from(),
                    dm.content(),
                    dm.action(),
                    dm.value(),
                    expiresAt != null ? expiresAt.toString() : null);
            connectionManager.broadcast(serializer.toJson(activeMsg));

            // Ack to sender
            DirectiveAckMessage ack = new DirectiveAckMessage(directiveId, "accepted");
            connectionManager.send(sessionId, serializer.toJson(ack));
        } catch (Exception e) {
            log.warn("Failed to handle directive from '{}': {}", dm.from(), e.getMessage(), e);
        }
    }

    // ========================
    // Lifecycle state provider and drain action (EN-008: K8s health endpoints)
    // ========================

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the lifecycle state provider to the underlying {@link WebSocketServer}
     * so that K8s health endpoints can report the ensemble's current state.
     */
    @Override
    public void setLifecycleStateProvider(Supplier<EnsembleLifecycleState> provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        server.setLifecycleStateProvider(provider);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Forwards the drain action to the underlying {@link WebSocketServer} so that
     * the {@code POST /api/lifecycle/drain} endpoint can trigger ensemble shutdown.
     */
    @Override
    public void setDrainAction(Runnable drainAction) {
        Objects.requireNonNull(drainAction, "drainAction must not be null");
        server.setDrainAction(drainAction);
    }

    /**
     * Package-private for testing: returns {@code true} once the heartbeat scheduler has
     * been shut down and all its tasks have completed. Becomes {@code true} after
     * {@link #stop()} is called on a dashboard that was running.
     */
    boolean isHeartbeatSchedulerTerminated() {
        return heartbeatScheduler.isTerminated();
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
        private Path traceExportDir = null;
        private int maxRetainedRuns = 10;
        private Path workspacePath = null;

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
         * Configures a directory to which each ensemble run's trace is automatically
         * exported as {@code {ensembleId}.json}.
         *
         * <p>When set, {@code Ensemble.builder().webDashboard(dashboard)} automatically
         * wires a {@link net.agentensemble.trace.export.JsonTraceExporter} pointing to
         * this directory, so callers do not need a separate
         * {@code .traceExporter(new JsonTraceExporter(dir))} call on the ensemble builder.
         *
         * <p>The directory is created automatically on the first export if it does not
         * already exist. Export failures log a warning but do not fail the run.
         *
         * <pre>
         * // Convenience (equivalent behavior):
         * WebDashboard dashboard = WebDashboard.builder()
         *     .port(7329)
         *     .traceExportDir(Path.of("./traces"))
         *     .build();
         * Ensemble.builder()
         *     .webDashboard(dashboard)
         *     .build();
         *
         * // Manual equivalent:
         * Ensemble.builder()
         *     .webDashboard(WebDashboard.onPort(7329))
         *     .traceExporter(new JsonTraceExporter(Path.of("./traces")))
         *     .build();
         * </pre>
         *
         * @param traceExportDir the directory to write trace files into; may be {@code null}
         *                       to disable automatic export (the default)
         * @return this builder
         */
        public Builder traceExportDir(Path traceExportDir) {
            this.traceExportDir = traceExportDir;
            return this;
        }

        /**
         * Sets the maximum number of completed ensemble runs retained in the
         * late-join snapshot sent to newly connecting browsers.
         *
         * <p>When a new run starts and the total number of retained runs would exceed
         * this cap, the oldest run's events are evicted from the snapshot. This prevents
         * unbounded memory and message growth in long-running batch processes.
         *
         * <p>Default: 10. Must be &ge; 1.
         *
         * @param maxRetainedRuns the maximum number of runs to retain; must be &ge; 1
         * @return this builder
         * @throws IllegalArgumentException when {@code maxRetainedRuns} is less than 1
         */
        public Builder maxRetainedRuns(int maxRetainedRuns) {
            this.maxRetainedRuns = maxRetainedRuns;
            return this;
        }

        /**
         * Sets the workspace root path for the file browsing API endpoint.
         *
         * <p>When set, the dashboard exposes a {@code GET /api/workspace/files?path=<rel>}
         * endpoint that lists directory contents within this path. Path traversal outside
         * the workspace root is denied.
         *
         * @param workspacePath the workspace root directory; may be {@code null} to disable
         *                      the endpoint (the default)
         * @return this builder
         */
        public Builder workspacePath(Path workspacePath) {
            this.workspacePath = workspacePath;
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
            if (maxRetainedRuns < 1) {
                throw new IllegalArgumentException("maxRetainedRuns must be >= 1; got: " + maxRetainedRuns);
            }
            return new WebDashboard(this);
        }
    }
}
