package net.agentensemble.network.transport.delivery;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.agentensemble.network.transport.DeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Delivery handler that writes responses to a durable queue.
 *
 * <p>The queue destination is determined by the {@link DeliverySpec#address()} field, which
 * specifies the queue name. The actual write is delegated to a {@link BiConsumer} so that
 * any queue implementation (Redis Streams, SQS, in-memory) can be plugged in.
 *
 * <p>Thread-safety depends on the provided {@code queueWriter}.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod#QUEUE
 */
public final class QueueDeliveryHandler implements DeliveryHandler {

    private final BiConsumer<String, WorkResponse> queueWriter;

    /**
     * Create a queue delivery handler.
     *
     * @param queueWriter function that accepts (queueName, response) and writes to the queue;
     *                    must not be null
     */
    public QueueDeliveryHandler(BiConsumer<String, WorkResponse> queueWriter) {
        this.queueWriter = Objects.requireNonNull(queueWriter, "queueWriter must not be null");
    }

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.QUEUE;
    }

    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        String queueName = spec.address();
        if (queueName == null || queueName.isBlank()) {
            throw new IllegalArgumentException("QUEUE delivery requires a non-blank address (queue name)");
        }
        queueWriter.accept(queueName, response);
    }
}
