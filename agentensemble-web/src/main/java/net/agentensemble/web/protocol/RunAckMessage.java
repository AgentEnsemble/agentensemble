package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Server-to-client message acknowledging a submitted ensemble run.
 *
 * <p>Sent immediately when a run is accepted (before execution begins) or rejected (when
 * the concurrency limit is reached).
 *
 * @param requestId the client-provided request identifier; may be {@code null} for REST
 *                  submissions that do not supply one
 * @param runId     the server-assigned unique run identifier (e.g. {@code "run-7f3a2b"})
 * @param status    the initial run status: {@code "ACCEPTED"} or {@code "REJECTED"}
 * @param tasks     the number of tasks in the run (0 when rejected)
 * @param workflow  the inferred workflow type (e.g. {@code "SEQUENTIAL"}), or {@code null}
 *                  when not yet determined
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunAckMessage(String requestId, String runId, String status, int tasks, String workflow)
        implements ServerMessage {}
