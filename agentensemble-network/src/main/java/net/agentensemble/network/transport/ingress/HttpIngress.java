package net.agentensemble.network.transport.ingress;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.network.transport.IngressSource;
import net.agentensemble.web.protocol.WorkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingress source that exposes an HTTP endpoint for submitting work requests.
 *
 * <p>When started, an embedded Javalin HTTP server is created with a single
 * {@code POST /api/work} route. Clients submit JSON-serialized {@link WorkRequest}
 * bodies and receive a {@code 202 Accepted} response with the assigned request ID.
 *
 * <p>This ingress uses its own standalone Javalin instance, separate from the
 * WebDashboard's server, to keep the network module decoupled.
 *
 * <p>Thread-safe: Javalin handles concurrency internally.
 *
 * @see IngressSource
 */
public final class HttpIngress implements IngressSource {

    private static final Logger log = LoggerFactory.getLogger(HttpIngress.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final int port;
    private final String host;
    private volatile Javalin app;

    /**
     * Creates an HTTP ingress source bound to all interfaces on the given port.
     *
     * @param port the port to listen on; use 0 for an ephemeral port
     */
    public HttpIngress(int port) {
        this(port, "0.0.0.0");
    }

    /**
     * Creates an HTTP ingress source bound to the given host and port.
     *
     * @param port the port to listen on; use 0 for an ephemeral port
     * @param host the host to bind to; must not be null
     */
    public HttpIngress(int port, String host) {
        this.port = port;
        this.host = Objects.requireNonNull(host, "host must not be null");
    }

    @Override
    public String name() {
        return "http:" + port;
    }

    /**
     * Returns the actual port the server is listening on.
     *
     * <p>Only valid after {@link #start(Consumer)} has been called.
     *
     * @return the bound port
     * @throws IllegalStateException if the server is not started
     */
    public int boundPort() {
        Javalin a = app;
        if (a == null) {
            throw new IllegalStateException("HttpIngress not started");
        }
        return a.port();
    }

    @Override
    public void start(Consumer<WorkRequest> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        if (app != null) {
            return;
        }

        app = Javalin.create(config -> {
            config.startup.showJavalinBanner = false;

            config.routes.post("/api/work", ctx -> {
                try {
                    WorkRequest request = MAPPER.readValue(ctx.body(), WorkRequest.class);
                    sink.accept(request);
                    ctx.status(202);
                    ctx.json(Map.of("requestId", request.requestId(), "status", "ACCEPTED"));
                } catch (Exception e) {
                    log.warn("Failed to parse work request: {}", e.getMessage());
                    ctx.status(400);
                    ctx.json(Map.of(
                            "error",
                            "Bad Request",
                            "message",
                            e.getMessage() != null ? e.getMessage() : "Unknown error"));
                }
            });
        });

        app.start(host, port);
        log.info("HttpIngress started on {}:{}", host, app.port());
    }

    @Override
    public void stop() {
        Javalin a = app;
        if (a != null) {
            app = null;
            a.stop();
            log.info("HttpIngress stopped");
        }
    }
}
