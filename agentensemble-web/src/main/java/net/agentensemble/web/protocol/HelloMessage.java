package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import net.agentensemble.trace.ExecutionTrace;

/**
 * Sent by the server to a newly connected client.
 *
 * <p>Carries a snapshot of the current {@link ExecutionTrace} so that
 * late-joining clients can catch up with in-progress execution.
 *
 * <p>In long-running mode, also includes the ensemble's shared capabilities
 * so that connecting peers can discover available tasks and tools. The
 * {@code sharedCapabilities} field is {@code null} for one-shot ensembles,
 * maintaining backward compatibility with v2.x clients (which use
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} and simply ignore
 * unknown fields).
 *
 * @param snapshotTrace      the current execution state, or null if idle
 * @param sharedCapabilities list of shared tasks/tools, or null if not a
 *                           long-running ensemble
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HelloMessage(ExecutionTrace snapshotTrace, List<SharedCapabilityInfo> sharedCapabilities)
        implements ServerMessage {

    /**
     * Backward-compatible constructor for one-shot ensembles.
     *
     * @param snapshotTrace the current execution state, or null if idle
     */
    public HelloMessage(ExecutionTrace snapshotTrace) {
        this(snapshotTrace, null);
    }
}
