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
 * {@code null} for one-shot ensembles; existing v2.x clients remain compatible because
 * their Jackson {@code ObjectMapper} (configured with {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * disabled, as {@code MessageSerializer} does) simply ignores the new field.
 *
 * <p>The optional {@code recentIterations} field carries the last N LLM iteration pairs
 * (started + completed) per task, so that late-joining browsers can hydrate the conversation
 * panel without waiting for the next live iteration event. Clients that do not understand
 * this field simply ignore it (Jackson ignoreUnknown + NON_NULL).
 *
 * @param ensembleId         the current ensemble run ID; null if no run has started
 * @param startedAt          when the current run started; null if no run has started
 * @param snapshotTrace      JSON array of previously broadcast {@code ServerMessage}s used for
 *                           late-join replay; null if no run has started
 * @param sharedCapabilities shared tasks/tools for the capability handshake; null for one-shot
 *                           ensembles
 * @param recentIterations   recent LLM iteration snapshots for conversation hydration; null
 *                           when no iterations have been recorded
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelloMessage(
        String ensembleId,
        Instant startedAt,
        JsonNode snapshotTrace,
        List<SharedCapabilityInfo> sharedCapabilities,
        List<IterationSnapshot> recentIterations)
        implements ServerMessage {

    /**
     * Backward-compatible constructor for one-shot ensembles (no shared capabilities,
     * no iteration snapshots).
     *
     * @param ensembleId    the current ensemble run ID; null if no run has started
     * @param startedAt     when the current run started; null if no run has started
     * @param snapshotTrace JSON array of previously broadcast messages for late-join replay;
     *                      null if no run has started
     */
    public HelloMessage(String ensembleId, Instant startedAt, JsonNode snapshotTrace) {
        this(ensembleId, startedAt, snapshotTrace, null, null);
    }

    /**
     * Backward-compatible constructor for ensembles with shared capabilities but no
     * iteration snapshots.
     *
     * @param ensembleId         the current ensemble run ID; null if no run has started
     * @param startedAt          when the current run started; null if no run has started
     * @param snapshotTrace      JSON array of previously broadcast messages for late-join replay
     * @param sharedCapabilities shared tasks/tools for the capability handshake; null for
     *                           one-shot ensembles
     */
    public HelloMessage(
            String ensembleId,
            Instant startedAt,
            JsonNode snapshotTrace,
            List<SharedCapabilityInfo> sharedCapabilities) {
        this(ensembleId, startedAt, snapshotTrace, sharedCapabilities, null);
    }
}
