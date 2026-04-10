package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Server-to-client acknowledgement of a {@link RunControlMessage}.
 *
 * <p>Status values:
 * <ul>
 *   <li>{@code "CANCELLING"} -- cancel request accepted; run will stop at next task boundary</li>
 *   <li>{@code "APPLIED"} -- model switch applied immediately</li>
 *   <li>{@code "REJECTED"} -- action not applicable (e.g. run already completed)</li>
 *   <li>{@code "NOT_FOUND"} -- runId is unknown</li>
 *   <li>{@code "INVALID_MODEL"} -- the requested model alias is not in the catalog</li>
 *   <li>{@code "INVALID_ACTION"} -- the action field is not recognised</li>
 * </ul>
 *
 * @param runId         the run that was targeted
 * @param action        the action from the client message
 * @param status        outcome of the control request (see above)
 * @param model         the new model alias (only for {@code switch_model} success)
 * @param previousModel the previous model alias (only for {@code switch_model} success)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunControlAckMessage(String runId, String action, String status, String model, String previousModel)
        implements ServerMessage {}
