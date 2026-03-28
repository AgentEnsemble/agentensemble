package net.agentensemble.network.transport;

import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * SPI for delivering work responses to requesters.
 *
 * <p>Each handler is responsible for one {@link DeliveryMethod}. The handler is invoked
 * by the {@link DeliveryRegistry} when a work response needs to be delivered using
 * the handler's method.
 *
 * <p>Implementations must be thread-safe.
 *
 * @see DeliveryRegistry
 * @see DeliveryMethod
 */
public interface DeliveryHandler {

    /**
     * Returns the delivery method this handler supports.
     *
     * @return the delivery method; never null
     */
    DeliveryMethod method();

    /**
     * Deliver a work response using the method-specific transport.
     *
     * @param spec     the delivery specification (method + address); must not be null
     * @param response the work response to deliver; must not be null
     */
    void deliver(DeliverySpec spec, WorkResponse response);
}
