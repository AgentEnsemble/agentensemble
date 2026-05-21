package net.agentensemble.web.publisher;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link LiveEventPublisher} that connects to a {@code LiveEventHub} ingress endpoint over a
 * client WebSocket (using {@link java.net.http.HttpClient}, no extra dependency).
 *
 * <p>Auto-reconnects with exponential backoff on disconnect. Outgoing envelopes are queued in a
 * bounded ring; on overflow the oldest envelopes are dropped (observability, not durability).
 * Hub-originated {@link ReviewDecisionForwardMessage}s arrive on the inbound side and are
 * dispatched to the subscriber set by {@link #subscribeToReviewDecisions(Consumer)}.
 *
 * <p>Thread-safe.
 */
public final class WebSocketLiveEventPublisher extends AbstractLiveEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebSocketLiveEventPublisher.class);

    /** Backoff schedule for reconnect (seconds): 1, 2, 4, 8, 16, capped at 30. */
    private static final int[] BACKOFF_SECONDS = {1, 2, 4, 8, 16, 30};

    private final URI hubUri;
    private final int queueCapacity;
    private final ProducerInfo info;
    private final BlockingQueue<String> outbound;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicReference<Consumer<ReviewDecisionForwardMessage>> reviewSubscriber = new AtomicReference<>();
    private final MessageSerializer messageSerializer;
    // HttpClient and scheduler are reset on each start() so the publisher can cycle through
    // start/stop pairs. start() also resets the reconnect-attempt counter. Guarded by the
    // synchronized start()/stop() pair.
    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private int reconnectAttempt = 0;

    /**
     * Factory: connect to a hub at the given URI with the supplied producer identity. Default
     * queue capacity 1024.
     */
    public static WebSocketLiveEventPublisher connect(URI hubUri, ProducerInfo info) {
        return new WebSocketLiveEventPublisher(hubUri, info, new MessageSerializer(), 1024);
    }

    public WebSocketLiveEventPublisher(URI hubUri, ProducerInfo info, MessageSerializer serializer, int queueCapacity) {
        super(info, serializer);
        this.info = info;
        this.messageSerializer = serializer;
        this.hubUri = Objects.requireNonNull(hubUri, "hubUri must not be null");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be >= 1; got " + queueCapacity);
        }
        this.queueCapacity = queueCapacity;
        this.outbound = new ArrayBlockingQueue<>(queueCapacity);
    }

    @Override
    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            // Recreate the per-cycle resources so start() after stop() works. Constructors that
            // ran once at instantiation would leave a shut-down scheduler in place; rebuilding
            // here keeps the publisher restartable across an arbitrary number of cycles.
            this.httpClient = HttpClient.newHttpClient();
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agentensemble-publisher-ws-" + info.producerId());
                t.setDaemon(true);
                return t;
            });
            this.reconnectAttempt = 0;
            connectAndDrain();
        }
    }

    @Override
    public synchronized void stop() {
        if (running.compareAndSet(true, false)) {
            WebSocket ws = socket.getAndSet(null);
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "stopped");
            }
            ScheduledExecutorService current = scheduler;
            if (current != null) {
                current.shutdownNow();
                try {
                    if (!current.awaitTermination(200, TimeUnit.MILLISECONDS)) {
                        log.debug("Publisher scheduler did not shut down within 200ms");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                scheduler = null;
            }
            // Drop anything still in the outbound queue: it cannot reach the hub now that
            // the transport is shutting down, and retaining it would pin envelopes in
            // memory for the lifetime of the JVM.
            outbound.clear();
            HttpClient currentClient = httpClient;
            if (currentClient != null) {
                try {
                    currentClient.close();
                } catch (Exception e) {
                    log.debug("Publisher {} httpClient.close() failed: {}", info().producerId(), e.getMessage());
                }
                httpClient = null;
            }
        }
    }

    @Override
    public boolean isConnected() {
        WebSocket ws = socket.get();
        return running.get() && ws != null && !ws.isInputClosed() && !ws.isOutputClosed();
    }

    @Override
    public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber) {
        reviewSubscriber.set(subscriber);
    }

    @Override
    protected void publishEnvelope(LiveEventEnvelope envelope) {
        String json = serializer().toJson(envelope);
        if (!outbound.offer(json)) {
            // Drop oldest to make room
            outbound.poll();
            outbound.offer(json);
            log.warn(
                    "Publisher {} outbound queue full (capacity={}); dropped oldest envelope",
                    info().producerId(),
                    queueCapacity);
        }
        WebSocket ws = socket.get();
        if (ws != null && !ws.isOutputClosed()) {
            drainOutbound();
        }
    }

    private void connectAndDrain() {
        if (!running.get()) return;
        HttpClient client = httpClient;
        if (client == null) return;
        // Percent-encode the producerId so identifiers with `&`, `?`, spaces, or other
        // reserved characters travel safely as a query parameter.
        String encodedProducerId = URLEncoder.encode(info().producerId(), StandardCharsets.UTF_8);
        URI target = URI.create(
                hubUri.toString() + (hubUri.getQuery() == null ? "?" : "&") + "producerId=" + encodedProducerId);
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(target, new WsListener());
        try {
            WebSocket ws = future.get(5, TimeUnit.SECONDS);
            socket.set(ws);
            reconnectAttempt = 0;
            log.info("Publisher {} connected to hub at {}", info().producerId(), hubUri);
            drainOutbound();
        } catch (ExecutionException | InterruptedException | java.util.concurrent.TimeoutException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        ScheduledExecutorService current = scheduler;
        if (current == null || current.isShutdown()) return;
        int idx = Math.min(reconnectAttempt++, BACKOFF_SECONDS.length - 1);
        int delay = BACKOFF_SECONDS[idx];
        log.debug(
                "Publisher {} scheduling reconnect to {} in {}s (attempt {})",
                info().producerId(),
                hubUri,
                delay,
                reconnectAttempt);
        try {
            current.schedule(this::connectAndDrain, delay, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Concurrent stop() raced with us; safe to drop, the publisher is going down.
            log.debug("Publisher {} reconnect rejected (scheduler shutting down)", info().producerId());
        }
    }

    private void drainOutbound() {
        WebSocket ws = socket.get();
        if (ws == null) return;
        String json;
        while ((json = outbound.poll()) != null) {
            try {
                ws.sendText(json, true).join();
            } catch (RuntimeException e) {
                // Re-enqueue at head if possible, then attempt reconnect.
                outbound.offer(json);
                log.warn("Publisher {} send failed: {}", info().producerId(), e.getMessage());
                socket.set(null);
                scheduleReconnect();
                return;
            }
        }
    }

    /**
     * Listener for a single WebSocket session. Each session gets its own listener instance,
     * and so its own {@link #buffer}; this prevents reconnects from interleaving partial
     * frames from an old socket into the new socket's accumulation state.
     */
    private final class WsListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String json = buffer.toString();
                buffer.setLength(0);
                handleInbound(json);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.debug("Publisher {} ingress WS closed: code={} reason={}", info().producerId(), statusCode, reason);
            socket.set(null);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.debug("Publisher {} ingress WS error: {}", info().producerId(), error.getMessage());
            socket.set(null);
            scheduleReconnect();
        }
    }

    private void handleInbound(String json) {
        try {
            ReviewDecisionForwardMessage decision =
                    messageSerializer.fromJson(json, ReviewDecisionForwardMessage.class);
            Consumer<ReviewDecisionForwardMessage> sub = reviewSubscriber.get();
            if (sub != null) {
                sub.accept(decision);
            }
        } catch (IllegalArgumentException e) {
            log.debug("Publisher {} could not parse inbound payload: {}", info().producerId(), e.getMessage());
        }
    }
}
