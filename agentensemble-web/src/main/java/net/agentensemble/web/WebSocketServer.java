package net.agentensemble.web;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.agentensemble.ensemble.EnsembleLifecycleState;
import net.agentensemble.web.protocol.ClientMessage;
import net.agentensemble.web.protocol.HeartbeatMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.PingMessage;
import net.agentensemble.web.protocol.PongMessage;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import org.eclipse.jetty.websocket.api.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Embedded WebSocket server that backs the live dashboard.
 *
 * <p>Wraps Javalin to host:
 * <ul>
 *   <li>WebSocket endpoint at {@code ws://{host}:{port}/ws} for live event streaming</li>
 *   <li>{@code GET /api/status} returning server status JSON</li>
 *   <li>Static file serving for the built {@code agentensemble-viz} assets at {@code /}
 *       (the dist is embedded in the JAR under {@code /web/})</li>
 * </ul>
 *
 * <p>Package-private; lifecycle managed exclusively by {@link WebDashboard}.
 *
 * <p>Thread safety: {@link #start} and {@link #stop} are synchronized. Broadcast and
 * send operations delegate to {@link ConnectionManager} which is itself thread-safe.
 */
class WebSocketServer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketServer.class);

    /** Heartbeat interval in seconds. */
    static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    private final ConnectionManager connectionManager;
    private final MessageSerializer serializer;
    private final ScheduledExecutorService heartbeatScheduler;

    private Javalin app;
    private volatile int runningPort = -1;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /**
     * The scheduled heartbeat task; stored so it can be cancelled when the server stops to
     * prevent stale tasks from accumulating across stop/restart cycles.
     */
    private volatile ScheduledFuture<?> heartbeatFuture;

    /** Optional handler called for every parsed client message (e.g. review decisions). */
    private volatile Consumer<ClientMessage> clientMessageHandler;

    /** Supplier for the ensemble's lifecycle state (for health endpoints). Null in one-shot mode. */
    private volatile Supplier<EnsembleLifecycleState> lifecycleStateProvider;

    /** Action to trigger ensemble draining (for lifecycle/drain endpoint). Null if not configured. */
    private volatile Runnable drainAction;

    WebSocketServer(
            ConnectionManager connectionManager,
            MessageSerializer serializer,
            ScheduledExecutorService heartbeatScheduler) {
        this.connectionManager = connectionManager;
        this.serializer = serializer;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /**
     * Starts the server on the given port and host. Port 0 selects an ephemeral port; call
     * {@link #port()} after start to discover the actual port assigned.
     *
     * <p>Logs a warning when {@code host} is not {@code localhost}, because the dashboard then
     * accepts connections from any network interface.
     *
     * @param port the TCP port to listen on; 0 for ephemeral
     * @param host the host/interface to bind to (e.g. {@code localhost}, {@code 0.0.0.0})
     */
    synchronized void start(int port, String host) {
        if (running.get()) {
            log.debug("WebSocketServer.start() called but server is already running on port {}", runningPort);
            return;
        }

        if (!"localhost".equals(host) && !"127.0.0.1".equals(host) && !"::1".equals(host) && !"[::1]".equals(host)) {
            log.warn(
                    "WebDashboard is binding to host '{}'. The live dashboard will be accessible from "
                            + "remote clients. Ensure your network is appropriately secured.",
                    host);
        }

        final String boundHost = host;
        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;
            // Static files from viz dist embedded in the JAR under /web/
            // config.staticFiles.add("/web", io.javalin.http.staticfiles.Location.CLASSPATH);

            config.routes.ws("/ws", ws -> {
                ws.onConnect(ctx -> {
                    String origin = ctx.header("Origin");
                    if (!isOriginAllowed(origin, boundHost)) {
                        log.warn(
                                "Rejected WebSocket connection from origin '{}' (host binding: {})", origin, boundHost);
                        ctx.session.close(1008, "Origin not allowed", Callback.NOOP);
                        return;
                    }
                    connectionManager.onConnect(new JavalinWsSession(ctx));
                });

                ws.onMessage(ctx -> {
                    String message = ctx.message();
                    try {
                        ClientMessage parsed = serializer.fromJson(message, ClientMessage.class);
                        handleClientMessage(ctx, parsed);
                    } catch (Exception e) {
                        log.warn("Failed to parse client message: {}", message, e);
                    }
                });

                ws.onClose(ctx -> connectionManager.onDisconnect(ctx.sessionId()));
            });

            config.routes.get("/api/status", ctx -> {
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("status", "running");
                status.put("clients", connectionManager.sessionCount());
                status.put("port", runningPort > 0 ? runningPort : port);
                Supplier<EnsembleLifecycleState> lsp = lifecycleStateProvider;
                if (lsp != null) {
                    EnsembleLifecycleState state = lsp.get();
                    if (state != null) {
                        status.put("lifecycleState", state.name());
                    }
                }
                ctx.json(status);
            });

            config.routes.get("/api/health/live", ctx -> {
                ctx.status(200);
                ctx.json(Map.of("status", "UP"));
            });

            config.routes.get("/api/health/ready", ctx -> {
                Supplier<EnsembleLifecycleState> lsp = lifecycleStateProvider;
                EnsembleLifecycleState state = lsp != null ? lsp.get() : null;
                if (state == EnsembleLifecycleState.READY) {
                    ctx.status(200);
                    ctx.json(Map.of("status", "READY", "lifecycleState", "READY"));
                } else {
                    ctx.status(503);
                    String stateName = state != null ? state.name() : "UNKNOWN";
                    ctx.json(Map.of("status", "NOT_READY", "lifecycleState", stateName));
                }
            });

            config.routes.post("/api/lifecycle/drain", ctx -> {
                Runnable action = drainAction;
                if (action != null) {
                    action.run();
                    ctx.status(200);
                    ctx.json(Map.of("status", "DRAINING"));
                } else {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Drain not available (ensemble not in long-running mode)"));
                }
            });
        });

        app.start(host, port);

        runningPort = app.port();
        running.set(true);

        // Schedule heartbeat; guard body with isRunning() so that tasks scheduled before
        // stop() fires do not broadcast after the server is stopped or restarted. The
        // returned future is stored so stop() can cancel it, preventing stale tasks from
        // accumulating if the server is stopped and restarted multiple times.
        heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(
                () -> {
                    if (running.get()) {
                        sendHeartbeat();
                    }
                },
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        String displayHost = host.equals("0.0.0.0") ? "localhost" : host;
        log.info(
                "WebDashboard server started -- WebSocket: ws://{}:{}/ws | Status: http://{}:{}/api/status",
                displayHost,
                runningPort,
                displayHost,
                runningPort);
    }

    /** Stops the server. No-op if not running. */
    synchronized void stop() {
        if (!running.get()) {
            return;
        }
        running.set(false);

        // Cancel the scheduled heartbeat task. This prevents orphaned heartbeat tasks from
        // firing after the server has stopped, especially across stop/restart cycles.
        ScheduledFuture<?> hb = heartbeatFuture;
        if (hb != null) {
            hb.cancel(false);
            heartbeatFuture = null;
        }

        runningPort = -1;
        if (app != null) {
            app.stop();
            app = null;
        }
        log.info("WebDashboard server stopped.");
    }

    /** Returns true if the server is currently running and accepting connections. */
    boolean isRunning() {
        return running.get();
    }

    /** Returns the port the server is listening on, or -1 if not running. */
    int port() {
        return runningPort;
    }

    /**
     * Sets a handler to be called for every parsed {@link ClientMessage} received from any client.
     * Used by {@link WebReviewHandler} to receive review decisions.
     *
     * @param handler the message handler; may be null to clear
     */
    void setClientMessageHandler(Consumer<ClientMessage> handler) {
        this.clientMessageHandler = handler;
    }

    /**
     * Sets the lifecycle state provider for K8s health endpoints.
     *
     * @param provider supplier for the ensemble's lifecycle state; may be null
     */
    void setLifecycleStateProvider(Supplier<EnsembleLifecycleState> provider) {
        this.lifecycleStateProvider = provider;
    }

    /**
     * Sets the drain action for the {@code POST /api/lifecycle/drain} endpoint.
     *
     * @param action runnable that initiates graceful shutdown; may be null
     */
    void setDrainAction(Runnable action) {
        this.drainAction = action;
    }

    // ========================
    // Origin validation
    // ========================

    /**
     * Returns true if the given {@code origin} is acceptable for a server bound to {@code host}.
     *
     * <p>When {@code host} is {@code localhost} or {@code 127.0.0.1}, only localhost origins
     * ({@code localhost}, {@code 127.0.0.1}, {@code [::1]}) are accepted. This prevents
     * cross-site WebSocket hijacking (CSRF).
     *
     * <p>When {@code host} is anything else, all origins are accepted (security is delegated
     * to the network layer).
     *
     * @param origin the value of the HTTP {@code Origin} header; may be null
     * @param host   the configured server host binding
     * @return true if the origin is allowed
     */
    static boolean isOriginAllowed(String origin, String host) {
        boolean isLocalBinding =
                "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
        if (!isLocalBinding) {
            return true; // Non-local binding: accept any origin
        }
        if (origin == null || origin.isEmpty() || "null".equals(origin)) {
            return false;
        }
        // Parse the origin as a URI and compare the hostname exactly to prevent subdomain
        // bypass attacks such as http://localhost.evil.com or http://evil.com?q=localhost.
        try {
            String parsedHost = java.net.URI.create(origin).getHost();
            if (parsedHost == null) {
                return false;
            }
            String hostLc = parsedHost.toLowerCase(java.util.Locale.ROOT);
            // Java's URI.getHost() preserves brackets for IPv6 literals (e.g. "[::1]");
            // strip them before comparing so http://[::1]:3000 is correctly accepted.
            if (hostLc.startsWith("[") && hostLc.endsWith("]")) {
                hostLc = hostLc.substring(1, hostLc.length() - 1);
            }
            return "localhost".equals(hostLc) || "127.0.0.1".equals(hostLc) || "::1".equals(hostLc);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ========================
    // Private helpers
    // ========================

    private void sendHeartbeat() {
        try {
            String json = serializer.toJson(new HeartbeatMessage(System.currentTimeMillis()));
            connectionManager.broadcast(json);
        } catch (Exception e) {
            log.warn("Error sending heartbeat", e);
        }
    }

    private void handleClientMessage(WsContext ctx, ClientMessage message) {
        if (message instanceof PingMessage) {
            String pong = serializer.toJson(new PongMessage());
            ctx.send(pong);
            return;
        }
        // Forward to any registered handler (e.g. WebReviewHandler for review decisions)
        Consumer<ClientMessage> handler = this.clientMessageHandler;
        if (handler != null) {
            try {
                handler.accept(message);
            } catch (Exception e) {
                log.warn("Client message handler threw an exception", e);
            }
        } else if (message instanceof ReviewDecisionMessage) {
            log.warn("Received review_decision but no client message handler is registered. "
                    + "Register WebDashboard via Ensemble.builder().webDashboard(dashboard).");
        }
    }

    // ========================
    // Javalin-backed WsSession implementation
    // ========================

    private static final class JavalinWsSession implements WsSession {
        private final WsContext ctx;

        JavalinWsSession(WsContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public String id() {
            return ctx.sessionId();
        }

        @Override
        public boolean isOpen() {
            return ctx.session.isOpen();
        }

        @Override
        public void send(String message) {
            try {
                if (ctx.session.isOpen()) {
                    ctx.send(message);
                }
            } catch (Exception e) {
                Logger wsLog = LoggerFactory.getLogger(JavalinWsSession.class);
                if (wsLog.isDebugEnabled()) {
                    wsLog.debug("Failed to send message to session {}: {}", id(), e.getMessage());
                }
            }
        }
    }
}
