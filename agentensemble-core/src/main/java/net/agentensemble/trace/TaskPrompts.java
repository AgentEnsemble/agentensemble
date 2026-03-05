package net.agentensemble.trace;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * The system and user prompts sent to the LLM for a task execution.
 *
 * <p>Captures the exact text that was sent to the model before the first LLM call.
 * Included in {@link TaskTrace} to enable post-mortem prompt analysis.
 */
@Value
@Builder(toBuilder = true)
public class TaskPrompts {

    /**
     * The system prompt establishing the agent's identity, role, and instructions.
     * Built by {@link net.agentensemble.agent.AgentPromptBuilder#buildSystemPrompt(net.agentensemble.Agent)}.
     */
    @NonNull
    String systemPrompt;

    /**
     * The user prompt presenting the task description, context from prior tasks,
     * and any injected memory sections.
     * Built by {@link net.agentensemble.agent.AgentPromptBuilder#buildUserPrompt}.
     */
    @NonNull
    String userPrompt;
}
