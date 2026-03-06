package net.agentensemble.web.protocol;

import java.time.Instant;

/**
 * Sent when {@code Ensemble.run()} returns normally.
 *
 * @param ensembleId     the ensemble run ID
 * @param completedAt    when execution finished
 * @param durationMs     total elapsed time in milliseconds
 * @param exitReason     how the run ended: COMPLETED, USER_EXIT_EARLY, TIMEOUT, etc.
 * @param totalTokens    aggregate token count across all tasks (0 if not tracked)
 * @param totalToolCalls aggregate tool call count across all tasks
 */
public record EnsembleCompletedMessage(
        String ensembleId,
        Instant completedAt,
        long durationMs,
        String exitReason,
        long totalTokens,
        int totalToolCalls)
        implements ServerMessage {}
