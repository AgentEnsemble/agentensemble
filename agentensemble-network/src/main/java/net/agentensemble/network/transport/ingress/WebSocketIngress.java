package net.agentensemble.network.transport.ingress;

import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.network.transport.IngressSource;
import net.agentensemble.web.protocol.WorkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passive ingress source for work requests arriving via WebSocket.
 *
 * <p>Unlike {@link QueueIngress} and {@link HttpIngress}, this ingress source does not
 * actively poll or listen. Instead, the WebSocket layer (e.g., {@code WebDashboard} or
 * {@code WebSocketServer}) calls {@link #onWorkRequest(WorkRequest)} when a work request
 * arrives, and this class forwards it to the registered sink.
 *
 * <p>This design allows the existing WebSocket infrastructure to participate in the
 * pluggable ingress framework without requiring a second WebSocket server.
 *
 * <p>Thread-safe: the sink reference is volatile.
 *
 * @see IngressSource
 */
public final class WebSocketIngress implements IngressSource {

    private static final Logger log = LoggerFactory.getLogger(WebSocketIngress.class);

    private final String ingressName;
    private volatile Consumer<WorkRequest> sink;

    /**
     * Creates a WebSocket ingress source with the given name.
     *
     * @param name a human-readable name for this ingress; must not be null
     */
    public WebSocketIngress(String name) {
        this.ingressName = Objects.requireNonNull(name, "name must not be null");
    }

    @Override
    public String name() {
        return "websocket:" + ingressName;
    }

    @Override
    public void start(Consumer<WorkRequest> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        this.sink = sink;
        log.info("WebSocketIngress '{}' started", ingressName);
    }

    @Override
    public void stop() {
        this.sink = null;
        log.info("WebSocketIngress '{}' stopped", ingressName);
    }

    /**
     * Called by the WebSocket layer when a work request arrives.
     *
     * <p>If this ingress source has been started, the request is pushed to the
     * registered sink. If not started (or already stopped), the request is silently
     * dropped.
     *
     * @param request the incoming work request; must not be null
     */
    public void onWorkRequest(WorkRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Consumer<WorkRequest> s = sink;
        if (s != null) {
            s.accept(request);
        }
    }
}
