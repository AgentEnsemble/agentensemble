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

    /**
     * Optional handler that receives client messages along with the session ID of the sender.
     * Takes precedence over {@link #clientMessageHandler} when set.
     */
    private volatile java.util.function.BiConsumer<String, ClientMessage> sessionAwareClientMessageHandler;

    /** Supplier for the ensemble's lifecycle state (for health endpoints). Null in one-shot mode. */
    private volatile Supplier<EnsembleLifecycleState> lifecycleStateProvider;

    /** Action to trigger ensemble draining (for lifecycle/drain endpoint). Null if not configured. */
    private volatile Runnable drainAction;

    /** Optional workspace root path for file browsing endpoints. Null if not configured. */
    private volatile java.nio.file.Path workspacePath;

    // ========================
    // Ensemble Control API fields (Phase 1)
    // ========================

    /** RunManager for tracking and executing API-submitted runs. */
    private volatile RunManager runManager;

    /**
     * Parser for Level 2/3 run request bodies. Used by {@code POST /api/runs} to
     * resolve execution ensembles with task overrides or dynamic task definitions.
     */
    private volatile RunRequestParser runRequestParser;

    /** Tool registry for the capabilities endpoint. Null when not configured. */
    private volatile ToolCatalog toolCatalog;

    /** Model registry for the capabilities endpoint. Null when not configured. */
    private volatile ModelCatalog modelCatalog;

    /**
     * Supplier for the template ensemble (set via WebDashboard.setEnsemble()).
     * Used by POST /api/runs to obtain the ensemble to run.
     */
    private volatile java.util.function.Supplier<net.agentensemble.Ensemble> ensembleSupplier;

    /**
     * SSE handler for GET /api/runs/{runId}/events (Phase 4).
     */
    private volatile SseHandler sseHandler;

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
        final boolean isLocalBinding =
                "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
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
                // Restrict lifecycle endpoints to localhost when bound to a local interface
                if (isLocalBinding) {
                    String remoteAddr = ctx.req().getRemoteAddr();
                    boolean isLocalClient = "127.0.0.1".equals(remoteAddr)
                            || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                            || "::1".equals(remoteAddr);
                    if (!isLocalClient) {
                        ctx.status(403);
                        ctx.json(Map.of("error", "Lifecycle endpoints restricted to localhost"));
                        return;
                    }
                }
                Runnable action = drainAction;
                if (action != null) {
                    // Respond before triggering drain so the HTTP response is written
                    // before the server is potentially torn down by the drain action.
                    ctx.status(200);
                    ctx.json(Map.of("status", "DRAINING"));
                    Thread.startVirtualThread(action);
                } else {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Drain not available (ensemble not in long-running mode)"));
                }
            });

            config.routes.get("/api/workspace/files", ctx -> {
                java.nio.file.Path wsPath = workspacePath;
                if (wsPath == null) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Workspace not configured"));
                    return;
                }
                if (!isLocalBinding || !isLocalClient(ctx)) {
                    ctx.status(403);
                    ctx.json(Map.of("error", "Workspace endpoints restricted to localhost"));
                    return;
                }
                String relativePath = ctx.queryParam("path");
                if (relativePath == null) {
                    relativePath = "";
                }

                // Resolve with symlink protection: use toRealPath to prevent symlink escapes
                java.nio.file.Path wsReal;
                java.nio.file.Path dirReal;
                try {
                    wsReal = wsPath.toRealPath();
                    dirReal = wsPath.resolve(relativePath).normalize().toRealPath();
                } catch (java.io.IOException e) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Path not found"));
                    return;
                }
                if (!dirReal.startsWith(wsReal)) {
                    ctx.status(403);
                    ctx.json(Map.of("error", "Path traversal denied"));
                    return;
                }
                if (!java.nio.file.Files.isDirectory(dirReal)) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Not a directory"));
                    return;
                }

                java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
                try (var stream = java.nio.file.Files.list(dirReal)) {
                    stream.forEach(p -> {
                        Map<String, Object> entry = new java.util.HashMap<>();
                        entry.put("name", p.getFileName().toString());
                        entry.put("type", java.nio.file.Files.isDirectory(p) ? "directory" : "file");
                        try {
                            entry.put("size", java.nio.file.Files.size(p));
                            entry.put(
                                    "lastModified",
                                    java.nio.file.Files.getLastModifiedTime(p)
                                            .toInstant()
                                            .toString());
                        } catch (java.io.IOException ignored) {
                            // Skip metadata if unavailable
                        }
                        entries.add(entry);
                    });
                }
                ctx.json(entries);
            });

            config.routes.get("/api/workspace/file", ctx -> {
                java.nio.file.Path wsPath = workspacePath;
                if (wsPath == null) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Workspace not configured"));
                    return;
                }
                if (!isLocalBinding || !isLocalClient(ctx)) {
                    ctx.status(403);
                    ctx.json(Map.of("error", "Workspace endpoints restricted to localhost"));
                    return;
                }
                String relativePath = ctx.queryParam("path");
                if (relativePath == null || relativePath.isEmpty()) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "Missing 'path' query parameter"));
                    return;
                }

                java.nio.file.Path wsReal;
                java.nio.file.Path fileReal;
                try {
                    wsReal = wsPath.toRealPath();
                    fileReal = wsPath.resolve(relativePath).normalize().toRealPath();
                } catch (java.io.IOException e) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "File not found"));
                    return;
                }
                if (!fileReal.startsWith(wsReal)) {
                    ctx.status(403);
                    ctx.json(Map.of("error", "Path traversal denied"));
                    return;
                }
                if (!java.nio.file.Files.isRegularFile(fileReal)) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "Not a file"));
                    return;
                }
                // Limit to 1MB to prevent serving large binaries
                long size = java.nio.file.Files.size(fileReal);
                if (size > 1_048_576) {
                    ctx.status(413);
                    ctx.json(Map.of("error", "File too large (max 1MB)"));
                    return;
                }
                try {
                    ctx.contentType("text/plain; charset=utf-8");
                    ctx.result(java.nio.file.Files.readString(fileReal, java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    ctx.status(500);
                    ctx.json(Map.of("error", "Failed to read file"));
                }
            });

            // ========================
            // Ensemble Control API endpoints (Phase 1)
            // ========================

            // POST /api/runs -- submit a run (Level 1/2/3 parameterization)
            config.routes.post("/api/runs", ctx -> {
                // RunManager is always created by WebDashboard; use it directly.
                RunManager rm = runManager;

                java.util.function.Supplier<net.agentensemble.Ensemble> supplier = ensembleSupplier;
                net.agentensemble.Ensemble template = supplier != null ? supplier.get() : null;
                if (template == null) {
                    ctx.status(503);
                    ctx.json(
                            Map.of(
                                    "error",
                                    "NOT_CONFIGURED",
                                    "message",
                                    "No template ensemble configured. Wire an Ensemble via Ensemble.builder().webDashboard(dashboard)."));
                    return;
                }

                com.fasterxml.jackson.databind.JsonNode body;
                try {
                    String bodyStr = ctx.body();
                    if (bodyStr == null || bodyStr.isBlank()) {
                        body = serializer.toJsonNode("{}");
                    } else {
                        body = serializer.toJsonNode(bodyStr);
                    }
                } catch (Exception e) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Invalid JSON body: " + e.getMessage()));
                    return;
                }
                // toJsonNode returns null for invalid JSON (does not throw)
                if (body == null) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Invalid or unparseable JSON body"));
                    return;
                }

                Map<String, String> inputs = new java.util.LinkedHashMap<>();
                com.fasterxml.jackson.databind.JsonNode inputsNode = body.get("inputs");
                if (inputsNode != null && inputsNode.isObject()) {
                    inputsNode.fields().forEachRemaining(entry -> {
                        if (entry.getValue().isTextual()) {
                            inputs.put(entry.getKey(), entry.getValue().asText());
                        }
                    });
                }

                Map<String, Object> tags = new java.util.LinkedHashMap<>();
                com.fasterxml.jackson.databind.JsonNode tagsNode = body.get("tags");
                if (tagsNode != null && tagsNode.isObject()) {
                    tagsNode.fields()
                            .forEachRemaining(entry ->
                                    tags.put(entry.getKey(), entry.getValue().asText()));
                }

                // Determine execution ensemble (Level 1/2/3)
                net.agentensemble.Ensemble executionEnsemble;
                try {
                    executionEnsemble = resolveExecutionEnsemble(template, body, runRequestParser);
                } catch (IllegalArgumentException e) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
                    return;
                } catch (IllegalStateException e) {
                    ctx.status(503);
                    ctx.json(Map.of("error", "NOT_CONFIGURED", "message", e.getMessage()));
                    return;
                }

                RunState state = rm.submitRun(executionEnsemble, inputs, tags, null, null, null);

                if (state.getStatus() == RunState.Status.REJECTED) {
                    ctx.status(429);
                    ctx.json(Map.of(
                            "error",
                            "CONCURRENCY_LIMIT",
                            "message",
                            "Maximum concurrent runs (" + rm.getMaxConcurrentRuns() + ") reached. Retry later.",
                            "retryAfterMs",
                            5000));
                    return;
                }

                ctx.status(202);
                ctx.json(Map.of(
                        "runId", state.getRunId(),
                        "status", RunState.Status.ACCEPTED.name(),
                        "tasks", state.getTaskCount(),
                        "workflow", state.getWorkflow() != null ? state.getWorkflow() : "SEQUENTIAL"));
            });

            // GET /api/runs -- list retained runs with optional status/tag filtering
            config.routes.get("/api/runs", ctx -> {
                RunManager rm = runManager;
                if (rm == null) {
                    ctx.status(503);
                    ctx.json(Map.of("error", "NOT_CONFIGURED", "message", "Ensemble Control API not configured."));
                    return;
                }

                String statusParam = ctx.queryParam("status");
                RunState.Status statusFilter = null;
                if (statusParam != null && !statusParam.isBlank()) {
                    try {
                        statusFilter = RunState.Status.valueOf(statusParam.toUpperCase(java.util.Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        ctx.status(400);
                        ctx.json(Map.of("error", "BAD_REQUEST", "message", "Unknown status: " + statusParam));
                        return;
                    }
                }

                String tagParam = ctx.queryParam("tag");
                String tagKey = null;
                String tagValue = null;
                if (tagParam != null && !tagParam.isBlank()) {
                    int colonIdx = tagParam.indexOf(':');
                    if (colonIdx > 0) {
                        tagKey = tagParam.substring(0, colonIdx);
                        tagValue = tagParam.substring(colonIdx + 1);
                    } else {
                        tagKey = tagParam;
                    }
                }

                java.util.List<RunState> runs = rm.listRuns(statusFilter, tagKey, tagValue);
                java.util.List<Map<String, Object>> runList = new java.util.ArrayList<>();
                for (RunState rs : runs) {
                    Map<String, Object> summary = new java.util.LinkedHashMap<>();
                    summary.put("runId", rs.getRunId());
                    summary.put("status", rs.getStatus().name());
                    summary.put("startedAt", rs.getStartedAt().toString());
                    if (rs.getCompletedAt() != null) {
                        summary.put(
                                "durationMs",
                                rs.getCompletedAt().toEpochMilli()
                                        - rs.getStartedAt().toEpochMilli());
                    } else {
                        summary.put("durationMs", null);
                    }
                    summary.put("taskCount", rs.getTaskCount());
                    summary.put("completedTasks", rs.getCompletedTasks());
                    if (rs.getWorkflow() != null) {
                        summary.put("workflow", rs.getWorkflow());
                    }
                    summary.put("tags", rs.getTags());
                    runList.add(summary);
                }

                Map<String, Object> response = new java.util.LinkedHashMap<>();
                response.put("runs", runList);
                response.put("total", runList.size());
                ctx.json(response);
            });

            // GET /api/runs/{runId} -- get full detail for a specific run
            config.routes.get("/api/runs/{runId}", ctx -> {
                RunManager rm = runManager;
                if (rm == null) {
                    ctx.status(503);
                    ctx.json(Map.of("error", "NOT_CONFIGURED", "message", "Ensemble Control API not configured."));
                    return;
                }

                String runId = ctx.pathParam("runId");
                java.util.Optional<RunState> found = rm.getRun(runId);
                if (found.isEmpty()) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "RUN_NOT_FOUND", "message", "No run with ID " + runId));
                    return;
                }

                RunState rs = found.get();
                Map<String, Object> detail = new java.util.LinkedHashMap<>();
                detail.put("runId", rs.getRunId());
                detail.put("status", rs.getStatus().name());
                detail.put("startedAt", rs.getStartedAt().toString());
                if (rs.getCompletedAt() != null) {
                    detail.put("completedAt", rs.getCompletedAt().toString());
                    detail.put(
                            "durationMs",
                            rs.getCompletedAt().toEpochMilli()
                                    - rs.getStartedAt().toEpochMilli());
                }
                if (rs.getWorkflow() != null) {
                    detail.put("workflow", rs.getWorkflow());
                }
                detail.put("inputs", rs.getInputs());
                detail.put("tags", rs.getTags());

                // Task output snapshots
                java.util.List<Map<String, Object>> tasksList = new java.util.ArrayList<>();
                for (RunState.TaskOutputSnapshot snap : rs.getTaskOutputs()) {
                    Map<String, Object> taskDetail = new java.util.LinkedHashMap<>();
                    taskDetail.put("taskDescription", snap.taskDescription());
                    taskDetail.put("output", snap.output());
                    if (snap.durationMs() != null) taskDetail.put("durationMs", snap.durationMs());
                    if (snap.tokenCount() != null && snap.tokenCount() != -1L)
                        taskDetail.put("tokenCount", snap.tokenCount());
                    taskDetail.put("toolCallCount", snap.toolCallCount());
                    tasksList.add(taskDetail);
                }
                detail.put("tasks", tasksList);

                if (rs.getMetrics() != null) {
                    Map<String, Object> metricsMap = new java.util.LinkedHashMap<>();
                    metricsMap.put("totalTokens", rs.getMetrics().getTotalTokens());
                    metricsMap.put("totalToolCalls", rs.getMetrics().getTotalToolCalls());
                    detail.put("metrics", metricsMap);
                }

                if (rs.getError() != null) {
                    detail.put("error", rs.getError());
                }

                ctx.json(detail);
            });

            // GET /api/capabilities -- list registered tools, models, and preconfigured tasks
            config.routes.get("/api/capabilities", ctx -> {
                Map<String, Object> capabilities = new java.util.LinkedHashMap<>();

                // Tools
                ToolCatalog tc = toolCatalog;
                if (tc != null) {
                    java.util.List<Map<String, String>> toolsList = new java.util.ArrayList<>();
                    for (ToolCatalog.ToolInfo info : tc.list()) {
                        toolsList.add(Map.of("name", info.name(), "description", info.description()));
                    }
                    capabilities.put("tools", toolsList);
                } else {
                    capabilities.put("tools", java.util.List.of());
                }

                // Models
                ModelCatalog mc = modelCatalog;
                if (mc != null) {
                    java.util.List<Map<String, String>> modelsList = new java.util.ArrayList<>();
                    for (ModelCatalog.ModelInfo info : mc.list()) {
                        modelsList.add(Map.of("alias", info.alias(), "provider", info.provider()));
                    }
                    capabilities.put("models", modelsList);
                } else {
                    capabilities.put("models", java.util.List.of());
                }

                // Preconfigured tasks from the template ensemble
                java.util.function.Supplier<net.agentensemble.Ensemble> supplier = ensembleSupplier;
                net.agentensemble.Ensemble template = supplier != null ? supplier.get() : null;
                if (template != null && template.getTasks() != null) {
                    java.util.List<Map<String, Object>> tasksList = new java.util.ArrayList<>();
                    for (net.agentensemble.Task t : template.getTasks()) {
                        Map<String, Object> taskInfo = new java.util.LinkedHashMap<>();
                        if (t.getName() != null) {
                            taskInfo.put("name", t.getName());
                        }
                        taskInfo.put("description", t.getDescription());
                        tasksList.add(taskInfo);
                    }
                    capabilities.put("preconfiguredTasks", tasksList);
                } else {
                    capabilities.put("preconfiguredTasks", java.util.List.of());
                }

                ctx.json(capabilities);
            });

            // ========================
            // Ensemble Control API endpoints (Phase 3: Run Control)
            // ========================

            // POST /api/runs/{runId}/cancel -- cooperatively cancel a run
            config.routes.post("/api/runs/{runId}/cancel", ctx -> {
                RunManager rm = runManager;
                String runId = ctx.pathParam("runId");
                String status = rm.cancelRun(runId);
                if ("NOT_FOUND".equals(status)) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "RUN_NOT_FOUND", "message", "No run with ID " + runId));
                } else if ("REJECTED".equals(status)) {
                    ctx.status(409);
                    ctx.json(Map.of(
                            "error", "RUN_COMPLETED", "message", "Run " + runId + " is already in a terminal state"));
                } else {
                    ctx.status(200);
                    ctx.json(Map.of("runId", runId, "status", status));
                }
            });

            // POST /api/runs/{runId}/model -- switch active model for a running ensemble
            config.routes.post("/api/runs/{runId}/model", ctx -> {
                ModelCatalog mc = modelCatalog;
                if (mc == null) {
                    ctx.status(503);
                    ctx.json(Map.of(
                            "error",
                            "NOT_CONFIGURED",
                            "message",
                            "Model catalog not configured. Add modelCatalog() to WebDashboard.builder()."));
                    return;
                }
                String runId = ctx.pathParam("runId");
                com.fasterxml.jackson.databind.JsonNode body;
                try {
                    body = serializer.toJsonNode(ctx.body());
                } catch (Exception e) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Invalid JSON body"));
                    return;
                }
                if (body == null || !body.has("model")) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Missing 'model' field"));
                    return;
                }
                String modelAlias = body.get("model").asText();
                java.util.Optional<dev.langchain4j.model.chat.ChatModel> modelOpt = mc.find(modelAlias);
                if (modelOpt.isEmpty()) {
                    ctx.status(400);
                    ctx.json(Map.of(
                            "error",
                            "INVALID_MODEL",
                            "message",
                            "Unknown model '" + modelAlias + "'. Available: "
                                    + mc.list().stream()
                                            .map(info -> info.alias())
                                            .collect(java.util.stream.Collectors.toList())));
                    return;
                }
                String status = runManager.switchModel(runId, modelOpt.get());
                if ("NOT_FOUND".equals(status)) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "RUN_NOT_FOUND", "message", "No run with ID " + runId));
                } else if ("REJECTED".equals(status)) {
                    ctx.status(409);
                    ctx.json(Map.of(
                            "error", "RUN_COMPLETED", "message", "Run " + runId + " is already in a terminal state"));
                } else {
                    ctx.status(200);
                    ctx.json(Map.of("runId", runId, "model", modelAlias, "status", status));
                }
            });

            // ========================
            // Ensemble Control API endpoints (Phase 4: SSE Event Stream)
            // ========================

            // GET /api/runs/{runId}/events -- SSE event stream for a specific run
            config.routes.sse("/api/runs/{runId}/events", client -> {
                SseHandler sse = sseHandler;
                if (sse == null) {
                    client.sendEvent("error", "{\"error\":\"NOT_CONFIGURED\"}");
                    client.close();
                    return;
                }
                sse.handle(client, client.ctx().pathParam("runId"));
            });

            // ========================
            // Ensemble Control API endpoints (Phase 5: REST Review + Inject + Tool Invoke)
            // ========================

            // POST /api/reviews/{reviewId} -- submit a review decision via REST
            config.routes.post("/api/reviews/{reviewId}", ctx -> {
                String reviewId = ctx.pathParam("reviewId");
                if (!connectionManager.hasPendingReview(reviewId)) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "REVIEW_NOT_FOUND", "message", "No pending review with ID " + reviewId));
                    return;
                }
                com.fasterxml.jackson.databind.JsonNode body;
                try {
                    body = serializer.toJsonNode(ctx.body());
                } catch (Exception e) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Invalid JSON body"));
                    return;
                }
                if (body == null || !body.has("decision")) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Missing 'decision' field"));
                    return;
                }
                // Build a ReviewDecisionMessage JSON and resolve via existing path
                String decision = body.get("decision").asText();
                String revisedOutput =
                        body.has("revisedOutput") ? body.get("revisedOutput").asText() : null;
                // Construct a ReviewDecisionMessage JSON to reuse the existing resolution path
                net.agentensemble.web.protocol.ReviewDecisionMessage rdm =
                        new net.agentensemble.web.protocol.ReviewDecisionMessage(reviewId, decision, revisedOutput);
                // Check if still pending (avoid race with timeout)
                if (!connectionManager.hasPendingReview(reviewId)) {
                    ctx.status(409);
                    ctx.json(Map.of("error", "REVIEW_RESOLVED", "message", "Review " + reviewId + " already resolved"));
                    return;
                }
                connectionManager.resolveReview(reviewId, serializer.toJson(rdm));
                ctx.status(200);
                ctx.json(Map.of("reviewId", reviewId, "decision", decision, "status", "APPLIED"));
            });

            // GET /api/reviews -- list pending review gates (optionally filtered by runId)
            config.routes.get("/api/reviews", ctx -> {
                String runIdFilter = ctx.queryParam("runId");
                java.util.List<ConnectionManager.PendingReviewInfo> reviews =
                        connectionManager.listPendingReviews(runIdFilter);
                java.util.List<Map<String, Object>> reviewList = new java.util.ArrayList<>();
                for (ConnectionManager.PendingReviewInfo info : reviews) {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>();
                    entry.put("reviewId", info.reviewId());
                    if (info.runId() != null) entry.put("runId", info.runId());
                    entry.put("taskDescription", info.taskDescription());
                    entry.put("taskOutput", info.taskOutput());
                    entry.put("timing", info.timing());
                    if (info.prompt() != null) entry.put("prompt", info.prompt());
                    entry.put("timeoutMs", info.timeoutMs());
                    entry.put("createdAt", info.createdAt().toString());
                    if (info.expiresAt() != null)
                        entry.put("expiresAt", info.expiresAt().toString());
                    reviewList.add(entry);
                }
                ctx.json(Map.of("reviews", reviewList, "total", reviewList.size()));
            });

            // POST /api/runs/{runId}/inject -- inject a context directive into a running ensemble
            config.routes.post("/api/runs/{runId}/inject", ctx -> {
                String runId = ctx.pathParam("runId");
                java.util.Optional<RunState> found = runManager.getRun(runId);
                if (found.isEmpty()) {
                    ctx.status(404);
                    ctx.json(Map.of("error", "RUN_NOT_FOUND", "message", "No run with ID " + runId));
                    return;
                }
                RunState state = found.get();
                net.agentensemble.Ensemble ens = state.getEnsemble();
                if (ens == null) {
                    ctx.status(409);
                    ctx.json(Map.of(
                            "error", "RUN_NOT_RUNNING", "message", "Run " + runId + " has not started execution yet"));
                    return;
                }
                com.fasterxml.jackson.databind.JsonNode body;
                try {
                    body = serializer.toJsonNode(ctx.body());
                } catch (Exception e) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Invalid JSON body"));
                    return;
                }
                if (body == null || !body.has("content")) {
                    ctx.status(400);
                    ctx.json(Map.of("error", "BAD_REQUEST", "message", "Missing 'content' field"));
                    return;
                }
                String content = body.get("content").asText();
                // target is stored in the directive's 'from' field for future routing; unused locally
                @SuppressWarnings("unused")
                String target = body.has("target") ? body.get("target").asText() : null;
                String directiveId = java.util.UUID.randomUUID().toString();
                net.agentensemble.directive.Directive directive = new net.agentensemble.directive.Directive(
                        directiveId, "api", content, null, null, java.time.Instant.now(), null);
                ens.getDirectiveStore().add(directive);
                ctx.status(200);
                ctx.json(Map.of("directiveId", directiveId, "status", "ACTIVE"));
            });

            // POST /api/tools/{name}/invoke -- directly invoke a tool from the catalog
            config.routes.post("/api/tools/{name}/invoke", ctx -> {
                ToolCatalog tc = toolCatalog;
                if (tc == null) {
                    ctx.status(503);
                    ctx.json(Map.of(
                            "error",
                            "NOT_CONFIGURED",
                            "message",
                            "Tool catalog not configured. Add toolCatalog() to WebDashboard.builder()."));
                    return;
                }
                String toolName = ctx.pathParam("name");
                java.util.Optional<net.agentensemble.tool.AgentTool> toolOpt = tc.find(toolName);
                if (toolOpt.isEmpty()) {
                    ctx.status(404);
                    ctx.json(Map.of(
                            "error",
                            "TOOL_NOT_FOUND",
                            "message",
                            "Unknown tool '" + toolName + "'. Available: "
                                    + tc.list().stream()
                                            .map(info -> info.name())
                                            .collect(java.util.stream.Collectors.toList())));
                    return;
                }
                net.agentensemble.tool.AgentTool tool = toolOpt.get();
                String input = "";
                try {
                    com.fasterxml.jackson.databind.JsonNode body = serializer.toJsonNode(ctx.body());
                    if (body != null && body.has("input")) {
                        input = body.get("input").asText();
                    }
                } catch (Exception ignored) {
                    /* use empty input */
                }

                long startMs = System.currentTimeMillis();
                final String toolInput = input;
                try {
                    net.agentensemble.tool.ToolResult result = java.util.concurrent.CompletableFuture.supplyAsync(
                                    () -> tool.execute(toolInput),
                                    java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                            .get(30, java.util.concurrent.TimeUnit.SECONDS);
                    long durationMs = System.currentTimeMillis() - startMs;
                    Map<String, Object> response = new java.util.LinkedHashMap<>();
                    response.put("tool", toolName);
                    response.put("status", "SUCCESS");
                    response.put("output", result.getOutput());
                    response.put("durationMs", durationMs);
                    ctx.json(response);
                } catch (java.util.concurrent.TimeoutException e) {
                    ctx.status(500);
                    ctx.json(Map.of(
                            "error", "TOOL_EXECUTION_FAILED", "message", "Tool execution timed out after 30 seconds"));
                } catch (Exception e) {
                    ctx.status(500);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    ctx.json(Map.of(
                            "error",
                            "TOOL_EXECUTION_FAILED",
                            "message",
                            cause.getMessage() != null
                                    ? cause.getMessage()
                                    : cause.getClass().getSimpleName()));
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
     * Sets a session-aware handler to be called for every parsed {@link ClientMessage}.
     * The handler receives the WebSocket session ID along with the message, enabling
     * targeted responses via {@link ConnectionManager#send(String, String)}.
     *
     * @param handler the session-aware message handler; may be null to clear
     */
    void setSessionAwareClientMessageHandler(java.util.function.BiConsumer<String, ClientMessage> handler) {
        this.sessionAwareClientMessageHandler = handler;
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

    /**
     * Sets the workspace root path for the {@code GET /api/workspace/files} endpoint.
     *
     * @param path the workspace root directory; may be null to disable the endpoint
     */
    void setWorkspacePath(java.nio.file.Path path) {
        this.workspacePath = path;
    }

    // ========================
    // Ensemble Control API setters (Phase 1)
    // ========================

    /**
     * Sets the {@link RunManager} for the {@code POST /api/runs} and {@code GET /api/runs}
     * endpoints.
     *
     * @param runManager the run manager; may be null to disable the Control API endpoints
     */
    void setRunManager(RunManager runManager) {
        this.runManager = runManager;
    }

    /**
     * Sets the {@link RunRequestParser} for parsing API run request bodies.
     *
     * @param runRequestParser the parser; may be null
     */
    void setRunRequestParser(RunRequestParser runRequestParser) {
        this.runRequestParser = runRequestParser;
    }

    /**
     * Sets the {@link ToolCatalog} for the {@code GET /api/capabilities} endpoint.
     *
     * @param toolCatalog the catalog; may be null
     */
    void setToolCatalog(ToolCatalog toolCatalog) {
        this.toolCatalog = toolCatalog;
    }

    /**
     * Sets the {@link ModelCatalog} for the {@code GET /api/capabilities} endpoint.
     *
     * @param modelCatalog the catalog; may be null
     */
    void setModelCatalog(ModelCatalog modelCatalog) {
        this.modelCatalog = modelCatalog;
    }

    /**
     * Sets the supplier that provides the template ensemble for API-submitted runs.
     *
     * @param supplier a supplier returning the current template ensemble; may be null
     */
    void setEnsembleSupplier(java.util.function.Supplier<net.agentensemble.Ensemble> supplier) {
        this.ensembleSupplier = supplier;
    }

    /**
     * Sets the {@link SseHandler} for the {@code GET /api/runs/{runId}/events} SSE endpoint.
     *
     * @param sseHandler the SSE handler; may be null to disable the SSE endpoint
     */
    void setSseHandler(SseHandler sseHandler) {
        this.sseHandler = sseHandler;
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

    /**
     * Check if the HTTP request originates from a local client.
     */
    private static boolean isLocalClient(io.javalin.http.Context ctx) {
        String remoteAddr = ctx.req().getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr) || "::1".equals(remoteAddr);
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

        // Try session-aware handler first (provides session ID for targeted responses)
        java.util.function.BiConsumer<String, ClientMessage> sessionHandler = this.sessionAwareClientMessageHandler;
        if (sessionHandler != null) {
            try {
                sessionHandler.accept(ctx.sessionId(), message);
            } catch (Exception e) {
                log.warn("Session-aware client message handler threw an exception", e);
            }
            return;
        }

        // Fall back to legacy handler (e.g. WebReviewHandler for review decisions)
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

    /**
     * Resolves the execution ensemble from the request body, applying Level 1/2/3 parsing.
     *
     * <p>Level detection (first match wins):
     * <ol>
     *   <li>If {@code tasks} array is present and non-empty: Level 3 (dynamic tasks).</li>
     *   <li>If {@code taskOverrides} object is present and non-empty: Level 2 (per-task overrides).</li>
     *   <li>Otherwise: Level 1 (template as-is).</li>
     * </ol>
     *
     * @throws IllegalArgumentException if task/tool/model references are invalid
     * @throws IllegalStateException    if a catalog is required but not configured
     */
    @SuppressWarnings("unchecked")
    private static net.agentensemble.Ensemble resolveExecutionEnsemble(
            net.agentensemble.Ensemble template,
            com.fasterxml.jackson.databind.JsonNode body,
            RunRequestParser parser) {
        // Shared ObjectMapper for converting JsonNode to typed collections
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        com.fasterxml.jackson.databind.JsonNode tasksNode = body.get("tasks");
        com.fasterxml.jackson.databind.JsonNode overridesNode = body.get("taskOverrides");

        // Reject requests where a level indicator is present but empty -- they would
        // silently fall through to Level 1 and run the template unexpectedly.
        if (tasksNode != null && tasksNode.isArray() && tasksNode.isEmpty()) {
            throw new IllegalArgumentException("The 'tasks' field is present but empty; "
                    + "provide a non-empty task list for Level 3, or omit the field.");
        }
        if (overridesNode != null && overridesNode.isObject() && overridesNode.isEmpty()) {
            throw new IllegalArgumentException("The 'taskOverrides' field is present but empty; "
                    + "provide at least one override for Level 2, or omit the field.");
        }

        RunRequestParser.RunConfiguration config;

        if (tasksNode != null && tasksNode.isArray() && !tasksNode.isEmpty()) {
            // Level 3: dynamic task list
            java.util.List<java.util.Map<String, Object>> taskDefs = mapper.convertValue(
                    tasksNode,
                    new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.List<java.util.Map<String, Object>>>() {});
            config = parser.buildFromDynamicTasks(template, taskDefs, null, null);
        } else if (overridesNode != null && overridesNode.isObject() && !overridesNode.isEmpty()) {
            // Level 2: per-task overrides
            java.util.Map<String, java.util.Map<String, Object>> taskOverrides = mapper.convertValue(
                    overridesNode,
                    new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.Map<String, java.util.Map<String, Object>>>() {});
            config = parser.buildFromTemplateWithOverrides(template, null, null, taskOverrides);
        } else {
            // Level 1: template as-is
            config = parser.buildFromTemplate(template, null, null);
        }

        return config.overrideTasks() != null ? template.withTasks(config.overrideTasks()) : config.template();
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
