package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Server-to-client acknowledgement of a {@link SubscribeMessage}.
 *
 * <p>Confirms the effective subscription that will be applied to this session from this point
 * forward.
 *
 * @param events the event types now active for this session; {@code ["*"]} means all events
 * @param runId  the run ID filter now active for this session; null if no run filter is set
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscribeAckMessage(List<String> events, String runId) implements ServerMessage {}
