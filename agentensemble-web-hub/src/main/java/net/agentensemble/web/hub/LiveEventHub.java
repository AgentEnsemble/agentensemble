package net.agentensemble.web.hub;

import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.agentensemble.web.protocol.HubHelloMessage;
import net.agentensemble.web.protocol.IterationSnapshot;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ProducerJoinedMessage;
import net.agentensemble.web.protocol.ProducerLeftMessage;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import net.agentensemble.web.publisher.LiveEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central server-side aggregator for distributed AgentEnsemble live observability.
 *
 * <p>The hub accepts {@link LiveEventEnvelope}s from many publisher processes and exposes a
 * single browser-facing WebSocket that delivers a coherent merged view of all active and
 * recently-active runs. Each envelope is rebroadcast verbatim to browsers (preserving producer
 * attribution) and recorded into the originating producer's snapshot for late-join replay.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code ws://host:port/ws} -- browser-facing WebSocket. On connect, the hub sends a
 *       {@link HubHelloMessage} containing all known producers and the flattened snapshot.</li>
 *   <li>{@code ws://host:port/ingress} -- publisher-facing WebSocket. Publishers connect with
 *       a {@code producerId} query parameter and stream JSON-serialized
 *       {@link LiveEventEnvelope}s. Hub sends {@link ReviewDecisionForwardMessage} back over
 *       the same socket.</li>
 *   <li>{@code GET /api/hub/producers} -- JSON list of known producers.</li>
 * </ul>
 *
 * <h2>In-process publishers</h2>
 * <p>For tests and same-JVM hub configurations, publishers may register directly with
 * {@link #registerPublisher(LiveEventPublisher)}; the hub bypasses the network for those.
 *
 * <p>Lifecycle: {@link #start()}/{@link #stop()} are idempotent. The hub registers a JVM
 * shutdown hook on first successful start.
 */
public final class LiveEventHub {

    private static final Logger log = LoggerFactory.getLogger(LiveEventHub.class);

    private final int port;
    private final String host;
    private final int maxRetainedProducers;
    private final int maxRetainedRunsPerProducer;
    private final int maxSnapshotIterationsPerProducer;
    private final Duration evictionIdleAfter;

    private final MessageSerializer serializer;
    private final ProducerRegistry registry;
    private final HubBroadcaster broadcaster;
    private final Map<String, IngressChannel> channels = new ConcurrentHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shutdownHookRegistered = new AtomicBoolean(false);
    private volatile Javalin app;
    private volatile int boundPort = -1;
    private final ScheduledExecutorService evictor;
    private ScheduledFuture<?> evictionTask;

    private LiveEventHub(Builder b) {
        this.port = b.port;
        this.host = b.host;
        this.maxRetainedProducers = b.maxRetainedProducers;
        this.maxRetainedRunsPerProducer = b.maxRetainedRunsPerProducer;
        this.maxSnapshotIterationsPerProducer = b.maxSnapshotIterationsPerProducer;
        this.evictionIdleAfter = b.evictionIdleAfter;
        this.serializer = new MessageSerializer();
        this.registry = new ProducerRegistry(
                serializer, maxRetainedProducers, maxRetainedRunsPerProducer, maxSnapshotIterationsPerProducer);
        this.broadcaster = new HubBroadcaster(serializer);
        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentensemble-web-hub-evictor");
            t.setDaemon(true);
            return t;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    // ========================
    // Lifecycle
    // ========================

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        Javalin instance = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;

            // Browser-facing WS endpoint
            config.routes.ws("/ws", ws -> {
                ws.onConnect(ctx -> {
                    broadcaster.register(ctx);
                    sendHubHello(ctx);
                });
                ws.onMessage(ctx -> handleBrowserMessage(ctx, ctx.message()));
                ws.onClose(ctx -> broadcaster.unregister(ctx));
            });

            // Publisher ingress WS endpoint
            config.routes.ws("/ingress", ws -> {
                ws.onConnect(ctx -> handleIngressConnect(ctx));
                ws.onMessage(ctx -> handleIngressMessage(ctx, ctx.message()));
                ws.onClose(ctx -> handleIngressClose(ctx));
            });

            // POST /api/hub/ingress -- one-way HTTP ingest for HttpLiveEventPublisher
            config.routes.post("/api/hub/ingress", ctx -> {
                String body = ctx.body();
                if (body == null || body.isBlank()) {
                    ctx.status(400);
                    return;
                }
                try {
                    LiveEventEnvelope envelope = serializer.fromJson(body, LiveEventEnvelope.class);
                    ingest(envelope);
                    ctx.status(202);
                } catch (RuntimeException e) {
                    log.warn("HTTP ingress dropped malformed envelope: {}", e.getMessage());
                    ctx.status(400);
                }
            });

            // GET /api/hub/producers
            config.routes.get("/api/hub/producers", ctx -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ProducerInfo p : registry.listProducers()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("producerId", p.producerId());
                    if (p.serviceName() != null) row.put("serviceName", p.serviceName());
                    if (p.instanceId() != null) row.put("instanceId", p.instanceId());
                    if (p.host() != null) row.put("host", p.host());
                    if (p.version() != null) row.put("version", p.version());
                    if (p.tags() != null) row.put("tags", p.tags());
                    rows.add(row);
                }
                ctx.json(Map.of("producers", rows, "total", rows.size()));
            });
        });
        instance.start(host, port);
        this.app = instance;
        this.boundPort = instance.port();
        this.running.set(true);
        log.info(
                "LiveEventHub started -- Browser: ws://{}:{}/ws | Ingress: ws://{}:{}/ingress",
                host,
                boundPort,
                host,
                boundPort);

        // Schedule idle eviction every 60 seconds.
        evictionTask = evictor.scheduleAtFixedRate(this::runEviction, 60, 60, TimeUnit.SECONDS);

        if (shutdownHookRegistered.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "agentensemble-web-hub-shutdown"));
        }
    }

    public synchronized void stop() {
        if (!running.get()) {
            return;
        }
        running.set(false);
        ScheduledFuture<?> et = evictionTask;
        if (et != null) {
            et.cancel(false);
            evictionTask = null;
        }
        evictor.shutdownNow();
        try {
            // No need to await; the executor uses daemon threads.
            evictor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Javalin instance = this.app;
        if (instance != null) {
            instance.stop();
            this.app = null;
        }
        this.boundPort = -1;
        log.info("LiveEventHub stopped.");
    }

    /**
     * Returns the actual port the hub is bound to, or {@code -1} when not running. Useful for
     * tests that build with {@code port(0)} and need the OS-assigned port after start.
     */
    public int actualPort() {
        if (!running.get()) {
            return -1;
        }
        return boundPort;
    }

    public boolean isRunning() {
        return running.get();
    }

    public ProducerRegistry producers() {
        return registry;
    }

    // ========================
    // Publisher registration (in-process)
    // ========================

    /**
     * Register an in-process {@link LiveEventPublisher}. The hub records the producer state and
     * accepts subsequent {@link #ingest(LiveEventEnvelope)} calls from this publisher. Review
     * decisions submitted by browsers are routed back via the publisher's
     * {@code deliverReviewDecision} method.
     *
     * @param publisher the publisher to register; must not be null
     */
    public void registerPublisher(LiveEventPublisher publisher) {
        Objects.requireNonNull(publisher, "publisher must not be null");
        ProducerInfo info = publisher.info();
        registry.getOrCreate(info);
        channels.put(info.producerId(), new InProcessIngressChannel(publisher));
        broadcaster.broadcastIfNew(info.producerId(), () -> new ProducerJoinedMessage(info, Instant.now()));
    }

    /**
     * Mark an in-process producer inactive. Snapshot state is retained for late-join until
     * eviction. Idempotent.
     *
     * @param producerId the producer ID
     * @param reason     human-readable reason; e.g. {@code "stopped"}
     */
    public void unregisterPublisher(String producerId, String reason) {
        Objects.requireNonNull(producerId, "producerId must not be null");
        registry.markInactive(producerId);
        channels.remove(producerId);
        broadcaster.announceLeft(producerId, () -> new ProducerLeftMessage(producerId, Instant.now(), reason));
    }

    // ========================
    // Envelope ingest
    // ========================

    /**
     * Process an envelope received from any source (in-process publisher or WS ingress).
     *
     * @param envelope the envelope; must not be null
     */
    public void ingest(LiveEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope must not be null");
        ProducerState state = registry.getOrCreate(envelope.producer());
        long previous = state.observeSequence(envelope.sequence());
        // previous < 0 means this is the first envelope from this producer (or after a reset);
        // skip the gap check in that case. After reconnect the publisher restarts at sequence 1
        // and we accept the discontinuity silently — the snapshot store retains its history,
        // not the sequence number.
        if (previous >= 0 && envelope.sequence() > previous + 1) {
            log.warn(
                    "Producer {} sequence gap: previous={}, incoming={}",
                    envelope.producer().producerId(),
                    previous,
                    envelope.sequence());
        }

        // Record into the producer's per-run snapshot. Sniff the inner message's type
        // discriminator to drive run-boundary and review-routing side effects, and to feed the
        // per-producer iteration ring buffer so HubHelloMessage.iterationsByProducer is
        // populated for late-join conversation hydration.
        String innerJson = envelope.message().toString();
        String innerType = extractType(envelope.message());
        switch (innerType) {
            case "ensemble_started" -> handleEnsembleStarted(state, envelope.message());
            case "review_requested" -> handleReviewRequested(state, envelope.message());
            case "llm_iteration_started" -> handleIterationStarted(state, envelope.message(), innerJson);
            case "llm_iteration_completed" -> handleIterationCompleted(state, envelope.message(), innerJson);
            default -> {
                /* nothing extra to do */
            }
        }
        // Append inner message to the per-producer snapshot, except for explicitly ephemeral
        // types (tokens, heartbeats). The embedded streaming listener's broadcastEphemeral
        // path already skips snapshot append; the hub must mirror that policy on the receive
        // side so token storms do not bloat the per-producer snapshot or noisy-up late-join
        // replays.
        if (!isEphemeralType(innerType)) {
            state.snapshot().appendToSnapshot(innerJson);
            // Log the envelope JSON + its receivedAt for deterministic flattened-snapshot
            // replay. Ephemeral types are intentionally excluded here too.
            String envelopeJson = serializer.toJson(envelope);
            state.appendEnvelope(envelope.receivedAt(), envelopeJson);
        }

        // Re-broadcast the whole envelope to browsers regardless of ephemeral status — live
        // token streaming still needs to reach connected browsers.
        broadcaster.broadcast(serializer.toJson(envelope));
    }

    private static boolean isEphemeralType(String type) {
        return "token".equals(type) || "heartbeat".equals(type);
    }

    private void handleEnsembleStarted(ProducerState state, JsonNode payload) {
        JsonNode ensembleId = payload.get("ensembleId");
        JsonNode startedAt = payload.get("startedAt");
        if (ensembleId != null && ensembleId.isTextual() && startedAt != null && startedAt.isTextual()) {
            try {
                state.snapshot().noteEnsembleStarted(ensembleId.asText(), Instant.parse(startedAt.asText()));
                state.snapshot().clearIterationSnapshots();
            } catch (RuntimeException e) {
                log.debug("Failed to parse ensemble_started startedAt: {}", e.getMessage());
            }
        }
    }

    private void handleReviewRequested(ProducerState state, JsonNode payload) {
        JsonNode reviewId = payload.get("reviewId");
        if (reviewId != null && reviewId.isTextual()) {
            state.recordPendingReview(reviewId.asText());
        }
    }

    private void handleIterationStarted(ProducerState state, JsonNode payload, String json) {
        String key = iterationKey(payload);
        if (key == null) return;
        try {
            LlmIterationStartedMessage msg = serializer.fromJson(json, LlmIterationStartedMessage.class);
            state.snapshot().recordIterationStarted(key, msg);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping unparseable llm_iteration_started for hub iteration buffer: {}", e.getMessage());
        }
    }

    private void handleIterationCompleted(ProducerState state, JsonNode payload, String json) {
        String key = iterationKey(payload);
        if (key == null) return;
        try {
            LlmIterationCompletedMessage msg = serializer.fromJson(json, LlmIterationCompletedMessage.class);
            state.snapshot().recordIterationCompleted(key, msg);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping unparseable llm_iteration_completed for hub iteration buffer: {}", e.getMessage());
        }
    }

    private static String iterationKey(JsonNode payload) {
        JsonNode agentRole = payload.get("agentRole");
        JsonNode taskDescription = payload.get("taskDescription");
        if (agentRole == null || !agentRole.isTextual() || taskDescription == null || !taskDescription.isTextual()) {
            return null;
        }
        return agentRole.asText() + ":" + taskDescription.asText();
    }

    // ========================
    // Browser-side handling
    // ========================

    private void sendHubHello(WsContext ctx) {
        List<ProducerInfo> producers = registry.listProducers();
        JsonNode snapshotTrace = buildFlattenedSnapshot();
        Map<String, List<IterationSnapshot>> iterations = collectIterationsByProducer();
        HubHelloMessage hello = new HubHelloMessage(
                producers.isEmpty() ? null : producers, snapshotTrace, iterations.isEmpty() ? null : iterations);
        ctx.send(serializer.toJson(hello));
    }

    private void handleBrowserMessage(WsContext ctx, String message) {
        if (message == null) return;
        try {
            // Try to recognize as a ReviewDecisionMessage by parsing as ClientMessage. We accept
            // raw JSON here rather than ClientMessage to keep this path tolerant of future
            // client extensions that the hub does not yet model.
            JsonNode parsed = serializer.toJsonNode(message);
            if (parsed == null) return;
            String type = parsed.has("type") ? parsed.get("type").asText() : null;
            if ("review_decision".equals(type)) {
                String reviewId =
                        parsed.has("reviewId") ? parsed.get("reviewId").asText() : null;
                if (reviewId == null) return;
                ProducerState owner = registry.findByPendingReviewId(reviewId);
                if (owner == null) {
                    log.debug("Received review_decision for unknown reviewId {}; ignoring", reviewId);
                    return;
                }
                owner.clearPendingReview(reviewId);
                IngressChannel ch = channels.get(owner.info().producerId());
                if (ch == null || !ch.isOpen()) {
                    log.warn(
                            "No ingress channel for producer {} to deliver review {}",
                            owner.info().producerId(),
                            reviewId);
                    return;
                }
                ch.deliverReviewDecision(new ReviewDecisionForwardMessage(reviewId, message));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to handle browser message: {}", e.getMessage());
        }
    }

    // ========================
    // Publisher ingress (WS)
    // ========================

    private void handleIngressConnect(WsContext ctx) {
        // Publishers are expected to send envelopes immediately; we accept any new session and
        // register the producer on the first envelope (which carries ProducerInfo).
        log.debug("Ingress WS connected: {}", ctx.sessionId());
    }

    private void handleIngressMessage(WsContext ctx, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        try {
            LiveEventEnvelope envelope = serializer.fromJson(message, LiveEventEnvelope.class);
            // Bind the WS context to this producer on first envelope (or re-bind if the same
            // producer reconnects on a new session).
            String producerId = envelope.producer().producerId();
            // Bind the WS context to this producer on first envelope, and rebind if a different
            // session is now publishing for the same producerId (a reconnect). Reference
            // equality on WsContext is the intended check here -- each WS upgrade allocates a
            // distinct context object even when the publisher reuses the same producerId.
            channels.compute(producerId, (id, existing) -> {
                if (existing instanceof WsIngressChannel wic && Objects.equals(wic.ctx, ctx)) {
                    return wic;
                }
                return new WsIngressChannel(ctx, envelope.producer(), serializer);
            });
            ingest(envelope);
            broadcaster.broadcastIfNew(producerId, () -> new ProducerJoinedMessage(envelope.producer(), Instant.now()));
        } catch (IllegalArgumentException e) {
            log.warn("Ingress dropped malformed envelope: {}", e.getMessage());
        }
    }

    private void handleIngressClose(WsContext ctx) {
        // Identity comparison via Objects.equals: WsContext does not override equals(), so
        // Objects.equals reduces to identity, which matches the intent (each WS upgrade
        // allocates a distinct context instance; a reconnect uses a fresh one).
        String evicted = null;
        for (Map.Entry<String, IngressChannel> e : channels.entrySet()) {
            if (e.getValue() instanceof WsIngressChannel wic && Objects.equals(wic.ctx, ctx)) {
                evicted = e.getKey();
                break;
            }
        }
        if (evicted == null) return;
        channels.remove(evicted);
        registry.markInactive(evicted);
        final String producerId = evicted;
        broadcaster.announceLeft(producerId, () -> new ProducerLeftMessage(producerId, Instant.now(), "disconnected"));
    }

    // ========================
    // Helpers
    // ========================

    private JsonNode buildFlattenedSnapshot() {
        // Collect each producer's retained envelope log and merge by the original receivedAt
        // stamped at ingest. This gives a stable, chronologically-correct late-join trace
        // across all producers — independent of the (non-deterministic) ConcurrentHashMap
        // iteration order of the registry. Ephemeral types (token, heartbeat) are excluded
        // because ingest() does not log them.
        List<ProducerState.EnvelopeLogEntry> merged = new ArrayList<>();
        for (ProducerState state : registry.states()) {
            merged.addAll(state.snapshotEnvelopes());
        }
        if (merged.isEmpty()) {
            return null;
        }
        merged.sort(Comparator.comparing(ProducerState.EnvelopeLogEntry::receivedAt));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < merged.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(merged.get(i).envelopeJson());
        }
        sb.append(']');
        return serializer.toJsonNode(sb.toString());
    }

    private Map<String, List<IterationSnapshot>> collectIterationsByProducer() {
        Map<String, List<IterationSnapshot>> result = new LinkedHashMap<>();
        for (ProducerState state : registry.states()) {
            List<IterationSnapshot> snapshots = state.snapshot().recentIterationsList();
            if (snapshots != null && !snapshots.isEmpty()) {
                result.put(state.info().producerId(), snapshots);
            }
        }
        return result;
    }

    private void runEviction() {
        try {
            registry.evictIdle(
                    evictionIdleAfter,
                    (producerId, reason) -> broadcaster.announceLeft(
                            producerId, () -> new ProducerLeftMessage(producerId, Instant.now(), reason)));
        } catch (RuntimeException e) {
            log.warn("Eviction sweep failed: {}", e.getMessage());
        }
    }

    private static String extractType(JsonNode message) {
        if (message == null) return "";
        JsonNode type = message.get("type");
        return type == null || !type.isTextual() ? "" : type.asText();
    }

    // ========================
    // IngressChannel impls
    // ========================

    private static final class InProcessIngressChannel implements IngressChannel {
        private final LiveEventPublisher publisher;

        InProcessIngressChannel(LiveEventPublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public ProducerInfo info() {
            return publisher.info();
        }

        @Override
        public void deliverReviewDecision(ReviewDecisionForwardMessage decision) {
            if (publisher instanceof InMemoryLiveEventPublisher inmem) {
                inmem.deliverReviewDecision(decision);
            }
        }

        @Override
        public boolean isOpen() {
            return publisher.isConnected();
        }
    }

    private static final class WsIngressChannel implements IngressChannel {
        final WsContext ctx;
        private final ProducerInfo info;
        private final MessageSerializer serializer;

        WsIngressChannel(WsContext ctx, ProducerInfo info, MessageSerializer serializer) {
            this.ctx = ctx;
            this.info = info;
            this.serializer = serializer;
        }

        @Override
        public ProducerInfo info() {
            return info;
        }

        @Override
        public void deliverReviewDecision(ReviewDecisionForwardMessage decision) {
            if (ctx.session.isOpen()) {
                ctx.send(serializer.toJson(decision));
            }
        }

        @Override
        public boolean isOpen() {
            return ctx.session.isOpen();
        }
    }

    // ========================
    // Builder
    // ========================

    public static final class Builder {
        private int port = 7400;
        private String host = "localhost";
        private int maxRetainedProducers = 50;
        private int maxRetainedRunsPerProducer = 10;
        private int maxSnapshotIterationsPerProducer = 5;
        private Duration evictionIdleAfter = Duration.ofMinutes(30);

        public Builder port(int port) {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("port must be 0..65535; got " + port);
            }
            this.port = port;
            return this;
        }

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host must not be null");
            return this;
        }

        public Builder maxRetainedProducers(int max) {
            this.maxRetainedProducers = max;
            return this;
        }

        public Builder maxRetainedRunsPerProducer(int max) {
            this.maxRetainedRunsPerProducer = max;
            return this;
        }

        public Builder maxSnapshotIterationsPerProducer(int max) {
            this.maxSnapshotIterationsPerProducer = max;
            return this;
        }

        public Builder evictionIdleAfter(Duration duration) {
            this.evictionIdleAfter = Objects.requireNonNull(duration, "duration must not be null");
            return this;
        }

        public LiveEventHub build() {
            return new LiveEventHub(this);
        }
    }

    // ========================
    // Browser broadcaster
    // ========================

    /**
     * Thin fan-out over connected browser WS sessions plus a tiny dedupe helper for joining
     * messages (we don't want to re-broadcast {@code producer_joined} every time the same
     * publisher pushes a new envelope, only on first-seen).
     */
    private static final class HubBroadcaster {
        private final MessageSerializer serializer;
        private final Map<String, WsContext> sessions = new ConcurrentHashMap<>();
        private final java.util.Set<String> announcedProducers = ConcurrentHashMap.newKeySet();

        HubBroadcaster(MessageSerializer serializer) {
            this.serializer = serializer;
        }

        void register(WsContext ctx) {
            sessions.put(ctx.sessionId(), ctx);
        }

        void unregister(WsContext ctx) {
            sessions.remove(ctx.sessionId());
        }

        void broadcast(String json) {
            for (WsContext ctx : sessions.values()) {
                try {
                    if (ctx.session.isOpen()) {
                        ctx.send(json);
                    }
                } catch (RuntimeException e) {
                    log.debug("Failed to send to browser session {}: {}", ctx.sessionId(), e.getMessage());
                }
            }
        }

        /**
         * Broadcast a {@code producer_joined} message only on first-seen for the given producer.
         * Pair with {@link #announceLeft(String, java.util.function.Supplier)} so reconnecting
         * producers are re-announced.
         */
        void broadcastIfNew(String producerId, java.util.function.Supplier<ProducerJoinedMessage> messageSupplier) {
            if (announcedProducers.add(producerId)) {
                ProducerJoinedMessage msg = messageSupplier.get();
                broadcast(serializer.toJson(msg));
            }
        }

        /**
         * Broadcast a {@code producer_left} message and forget the producer so a future
         * re-join triggers a fresh {@code producer_joined}.
         */
        void announceLeft(String producerId, java.util.function.Supplier<ProducerLeftMessage> messageSupplier) {
            if (announcedProducers.remove(producerId)) {
                broadcast(serializer.toJson(messageSupplier.get()));
            }
        }
    }
}
