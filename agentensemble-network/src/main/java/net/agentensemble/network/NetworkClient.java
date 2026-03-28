package net.agentensemble.network;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.web.protocol.ClientMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ServerMessage;
import net.agentensemble.web.protocol.TaskAcceptedMessage;
import net.agentensemble.web.protocol.TaskProgressMessage;
import net.agentensemble.web.protocol.TaskResponseMessage;
import net.agentensemble.web.protocol.ToolResponseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket client for communicating with a single remote ensemble.
 *
 * <p>Manages a WebSocket connection to a remote ensemble, sends request messages, and
 * correlates response messages back to pending futures by {@code requestId}.
 *
 * <p>Uses Java's built-in {@link java.net.http.WebSocket} (no external dependency).
 *
 * <p>Thread-safe: all mutable state is in {@link ConcurrentHashMap} and {@link AtomicReference}.
 */
public class NetworkClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkClient.class);

    private final String ensembleName;
    private final String wsUrl;
    private final Duration connectTimeout;
    private final MessageSerializer serializer;
    private final ConcurrentHashMap<String, CompletableFuture<ServerMessage>> pendingRequests =
            new ConcurrentHashMap<>();
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private final AtomicReference<HttpClient> httpClientRef = new AtomicReference<>();

    /**
     * Create a new client for the given ensemble.
     *
     * @param ensembleName   the ensemble name (for logging)
     * @param wsUrl          the WebSocket URL to connect to
     * @param connectTimeout timeout for establishing the WebSocket connection
     */
    public NetworkClient(String ensembleName, String wsUrl, Duration connectTimeout) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.wsUrl = Objects.requireNonNull(wsUrl, "wsUrl must not be null");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        this.serializer = new MessageSerializer();
    }

    /**
     * Package-private constructor for testing with a custom serializer.
     */
    NetworkClient(String ensembleName, String wsUrl, Duration connectTimeout, MessageSerializer serializer) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.wsUrl = Objects.requireNonNull(wsUrl, "wsUrl must not be null");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
    }

    /**
     * Send a client message to the remote ensemble and register a pending future for the response.
     *
     * <p>Lazily connects if not already connected. The returned future completes when a
     * response message with a matching {@code requestId} arrives.
     *
     * @param message   the client message to send
     * @param requestId the request correlation ID
     * @return a future that completes with the response ServerMessage
     * @throws IOException if the connection cannot be established
     */
    public CompletableFuture<ServerMessage> send(ClientMessage message, String requestId) throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            WebSocket ws = ensureConnected();
            String json = serializer.toJson(message);
            ws.sendText(json, true).join();
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            future.completeExceptionally(e);
            throw new IOException("Failed to send message to ensemble '" + ensembleName + "'", e);
        }

        return future;
    }

    /**
     * Close the WebSocket connection and fail all pending futures.
     */
    @Override
    public void close() {
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "client closing").join();
            } catch (Exception e) {
                log.debug("Error closing WebSocket to {}: {}", ensembleName, e.getMessage());
            }
        }

        HttpClient client = httpClientRef.getAndSet(null);
        if (client != null) {
            client.close();
        }

        failAllPending(new IOException("Client closed"));
    }

    /**
     * Returns the number of pending (in-flight) requests.
     */
    public int pendingCount() {
        return pendingRequests.size();
    }

    /**
     * Returns the target ensemble name.
     */
    public String ensembleName() {
        return ensembleName;
    }

    /**
     * Returns the WebSocket URL.
     */
    public String wsUrl() {
        return wsUrl;
    }

    // ========================
    // Connection management
    // ========================

    private WebSocket ensureConnected() throws IOException {
        WebSocket existing = wsRef.get();
        if (existing != null) {
            return existing;
        }

        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build();
            httpClientRef.set(client);

            WebSocket ws = client.newWebSocketBuilder()
                    .buildAsync(URI.create(wsUrl), new ResponseListener())
                    .join();
            wsRef.set(ws);
            log.debug("Connected to ensemble '{}' at {}", ensembleName, wsUrl);
            return ws;
        } catch (Exception e) {
            throw new IOException("Failed to connect to ensemble '" + ensembleName + "' at " + wsUrl, e);
        }
    }

    private void failAllPending(Exception cause) {
        pendingRequests.forEach((id, future) -> future.completeExceptionally(cause));
        pendingRequests.clear();
    }

    // ========================
    // WebSocket listener
    // ========================

    private class ResponseListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            // Request the first message; subsequent requests are made in onText().
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                handleMessage(json);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("Connection to ensemble '{}' closed: {} {}", ensembleName, statusCode, reason);
            wsRef.set(null);
            failAllPending(new IOException("Connection closed: " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("WebSocket error for ensemble '{}': {}", ensembleName, error.getMessage());
            wsRef.set(null);
            failAllPending(new IOException("Connection error", error));
        }

        private void handleMessage(String json) {
            try {
                ServerMessage msg = serializer.fromJson(json, ServerMessage.class);

                String requestId = extractRequestId(msg);
                if (requestId != null) {
                    CompletableFuture<ServerMessage> pending = pendingRequests.remove(requestId);
                    if (pending != null) {
                        pending.complete(msg);
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to parse message from ensemble '{}': {}", ensembleName, e.getMessage());
            }
        }

        private String extractRequestId(ServerMessage msg) {
            if (msg instanceof TaskResponseMessage r) return r.requestId();
            if (msg instanceof ToolResponseMessage r) return r.requestId();
            if (msg instanceof TaskAcceptedMessage r) {
                // Log acceptance but don't complete the pending future (wait for final response)
                log.debug("Task accepted by '{}': requestId={}", ensembleName, r.requestId());
                return null;
            }
            if (msg instanceof TaskProgressMessage r) {
                log.debug("Task progress from '{}': requestId={}, status={}", ensembleName, r.requestId(), r.status());
                return null;
            }
            return null;
        }
    }
}
