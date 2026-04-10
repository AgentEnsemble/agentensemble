package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Client-to-server message for subscribing to a filtered subset of server events.
 *
 * <p>When sent, the server will only deliver events whose type is in {@code events} to this
 * session. All events remain broadcast to unsubscribed sessions (backwards compatible).
 *
 * <p>Use {@code events: ["*"]} to reset to the default (all event types delivered).
 *
 * <p>Optional {@code runId}: when set, the server will additionally filter to events associated
 * with the specified run. Events that have no run association (heartbeat, hello) are always
 * delivered regardless of the run filter.
 *
 * @param events event type names (e.g. {@code ["task_started", "task_completed", "run_result"]})
 *               or {@code ["*"]} for all; must not be null
 * @param runId  optional run ID to restrict delivery to events from that run; null means no
 *               run-level filter
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscribeMessage(List<String> events, String runId) implements ClientMessage {}
