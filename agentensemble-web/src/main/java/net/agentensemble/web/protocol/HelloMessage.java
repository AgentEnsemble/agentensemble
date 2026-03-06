package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * Sent by the server to a client immediately upon connection.
 *
 * <p>Provides the current execution state for late-joining browsers. If the ensemble has not
 * started yet, all fields except {@code type} may be null.
 *
 * @param ensembleId    the current ensemble run ID; null if no run has started
 * @param startedAt     when the current run started; null if no run has started
 * @param snapshotTrace the current partial {@code ExecutionTrace} as a JSON tree; null if none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HelloMessage(String ensembleId, Instant startedAt, JsonNode snapshotTrace) implements ServerMessage {}
