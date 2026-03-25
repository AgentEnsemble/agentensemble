package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Sent by the server to a client immediately upon connection.
 *
 * <p>Provides the current execution state for late-joining browsers. If the ensemble has not
 * started yet, all fields except {@code type} may be null.
 *
 * <p>In long-running mode, also includes the ensemble's shared capabilities so that connecting
 * peers can discover available tasks and tools. The {@code sharedCapabilities} field is
 * {@code null} for one-shot ensembles; existing v2.x clients ignore it via
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 *
 * @param ensembleId         the current ensemble run ID; null if no run has started
 * @param startedAt          when the current run started; null if no run has started
 * @param snapshotTrace      the current partial {@code ExecutionTrace} as a JSON tree; null if none
 * @param sharedCapabilities shared tasks/tools for the capability handshake; null for one-shot
 *                           ensembles
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelloMessage(
        String ensembleId, Instant startedAt, JsonNode snapshotTrace, List<SharedCapabilityInfo> sharedCapabilities)
        implements ServerMessage {

    /**
     * Backward-compatible constructor for one-shot ensembles (no shared capabilities).
     *
     * @param ensembleId    the current ensemble run ID; null if no run has started
     * @param startedAt     when the current run started; null if no run has started
     * @param snapshotTrace the current partial execution trace; null if none
     */
    public HelloMessage(String ensembleId, Instant startedAt, JsonNode snapshotTrace) {
        this(ensembleId, startedAt, snapshotTrace, null);
    }
}
