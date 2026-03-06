package net.agentensemble.web.protocol;

import java.time.Instant;

/**
 * Sent when {@code Ensemble.run()} begins execution.
 *
 * @param ensembleId the unique ID for this ensemble run
 * @param startedAt  when execution started
 * @param totalTasks total number of tasks in this run
 * @param workflow   the effective workflow name (e.g. SEQUENTIAL, PARALLEL, HIERARCHICAL)
 */
public record EnsembleStartedMessage(String ensembleId, Instant startedAt, int totalTasks, String workflow)
        implements ServerMessage {}
