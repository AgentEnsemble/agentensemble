package net.agentensemble.network.transport.delivery;

import java.util.Objects;
import net.agentensemble.network.transport.DeliveryHandler;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivery handler that discards responses (fire-and-forget).
 *
 * <p>Used when the requester does not need a response. The handler logs at DEBUG level
 * to aid troubleshooting but otherwise performs no work.
 *
 * <p>Thread-safe: stateless.
 *
 * @see DeliveryHandler
 * @see DeliveryMethod#NONE
 */
public final class NoneDeliveryHandler implements DeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(NoneDeliveryHandler.class);

    @Override
    public DeliveryMethod method() {
        return DeliveryMethod.NONE;
    }

    @Override
    public void deliver(DeliverySpec spec, WorkResponse response) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(response, "response must not be null");
        log.debug("NONE delivery: discarding response for request {}", response.requestId());
    }
}
