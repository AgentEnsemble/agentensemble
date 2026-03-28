package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Specifies how a work request result should be delivered back to the requester.
 *
 * @param method  the delivery transport method; must not be null
 * @param address method-specific address (e.g., WebSocket URL, queue name, webhook URL);
 *                may be null for methods that do not require an explicit address
 *
 * @see DeliveryMethod
 * @see WorkRequest
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeliverySpec(DeliveryMethod method, String address) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if {@code method} is null
     */
    public DeliverySpec {
        Objects.requireNonNull(method, "method must not be null");
    }
}
