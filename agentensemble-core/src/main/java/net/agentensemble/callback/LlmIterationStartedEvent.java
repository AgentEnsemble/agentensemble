package net.agentensemble.callback;

import java.util.List;
import net.agentensemble.trace.CapturedMessage;

/**
 * Fired at the beginning of each ReAct iteration, just before the LLM is called.
 * Contains the full message buffer being sent to the LLM.
 */
public record LlmIterationStartedEvent(
        String agentRole, String taskDescription, int iterationIndex, List<CapturedMessage> messages) {}
