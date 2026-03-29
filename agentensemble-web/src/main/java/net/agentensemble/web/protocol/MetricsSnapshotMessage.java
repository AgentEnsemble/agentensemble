package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire message with cumulative metrics for a specific agent, sent after each LLM iteration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricsSnapshotMessage(
        String agentRole,
        int taskIndex,
        long inputTokens,
        long outputTokens,
        long llmLatencyMs,
        long toolExecutionTimeMs,
        int iterationCount,
        String costEstimate)
        implements ServerMessage {}
