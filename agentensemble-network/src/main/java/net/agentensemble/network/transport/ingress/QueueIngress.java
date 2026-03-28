package net.agentensemble.network.transport.ingress;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.agentensemble.network.transport.IngressSource;
import net.agentensemble.network.transport.RequestQueue;
import net.agentensemble.web.protocol.WorkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingress source that polls a {@link RequestQueue} for incoming work requests.
 *
 * <p>When started, a virtual thread is spawned that continuously polls the queue
 * with a one-second timeout and forwards dequeued requests to the configured sink.
 *
 * <p>Thread-safe: controlled via {@link AtomicBoolean}.
 *
 * @see IngressSource
 * @see RequestQueue
 */
public final class QueueIngress implements IngressSource {

    private static final Logger log = LoggerFactory.getLogger(QueueIngress.class);

    private final RequestQueue queue;
    private final String queueName;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Creates a queue ingress source.
     *
     * @param queue     the request queue to poll; must not be null
     * @param queueName the name of the queue to read from; must not be null
     */
    public QueueIngress(RequestQueue queue, String queueName) {
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.queueName = Objects.requireNonNull(queueName, "queueName must not be null");
    }

    @Override
    public String name() {
        return "queue:" + queueName;
    }

    @Override
    public void start(Consumer<WorkRequest> sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            log.info("QueueIngress '{}' polling started", queueName);
            while (running.get()) {
                try {
                    WorkRequest request = queue.dequeue(queueName, Duration.ofSeconds(1));
                    if (request != null) {
                        sink.accept(request);
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        log.warn("Error polling queue '{}': {}", queueName, e.getMessage());
                    }
                }
            }
            log.info("QueueIngress '{}' polling stopped", queueName);
        });
    }

    @Override
    public void stop() {
        running.set(false);
    }
}
