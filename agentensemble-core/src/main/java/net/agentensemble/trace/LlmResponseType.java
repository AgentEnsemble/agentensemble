package net.agentensemble.trace;

/**
 * Classifies the type of response returned by the LLM in a single ReAct iteration,
 * recorded in {@code LlmInteraction.getResponseType()}.
 */
public enum LlmResponseType {

    /**
     * The LLM requested one or more tool executions.
     * The {@code LlmInteraction.getToolCalls()} list is non-empty.
     */
    TOOL_CALLS,

    /**
     * The LLM produced a final text answer with no further tool calls.
     * The {@code LlmInteraction.getResponseText()} field contains the response.
     */
    FINAL_ANSWER
}
