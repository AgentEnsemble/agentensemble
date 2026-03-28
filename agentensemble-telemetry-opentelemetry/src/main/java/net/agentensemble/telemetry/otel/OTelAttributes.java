package net.agentensemble.telemetry.otel;

import static io.opentelemetry.api.common.AttributeKey.longKey;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

import io.opentelemetry.api.common.AttributeKey;

/**
 * Semantic attribute keys used by AgentEnsemble OpenTelemetry spans.
 *
 * <p>All keys are prefixed with {@code agentensemble.} to avoid conflicts with
 * other instrumentation libraries.
 */
public final class OTelAttributes {

    /** Name or ID of the ensemble run. */
    public static final AttributeKey<String> ENSEMBLE_NAME = stringKey("agentensemble.ensemble.name");

    /** Description of the task being executed. */
    public static final AttributeKey<String> TASK_DESCRIPTION = stringKey("agentensemble.task.description");

    /** Role of the agent executing a task or tool. */
    public static final AttributeKey<String> AGENT_ROLE = stringKey("agentensemble.agent.role");

    /** Role of the target agent in a delegation. */
    public static final AttributeKey<String> DELEGATION_TARGET = stringKey("agentensemble.delegation.target");

    /** Name of the tool being executed. */
    public static final AttributeKey<String> TOOL_NAME = stringKey("agentensemble.tool.name");

    /** 1-based index of the task within the workflow. */
    public static final AttributeKey<Long> TASK_INDEX = longKey("agentensemble.task.index");

    /** Token count for an LLM interaction. */
    public static final AttributeKey<Long> TOKEN_COUNT = longKey("agentensemble.token.count");

    /** Duration in milliseconds. */
    public static final AttributeKey<Long> DURATION_MS = longKey("agentensemble.duration_ms");

    private OTelAttributes() {}
}
