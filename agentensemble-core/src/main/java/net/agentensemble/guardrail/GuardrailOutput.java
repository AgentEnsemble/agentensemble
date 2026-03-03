package net.agentensemble.guardrail;

/**
 * The context object passed to an {@link OutputGuardrail} after agent execution completes.
 *
 * Carries everything the guardrail needs to decide whether the response should be
 * accepted: the raw LLM text, the typed parsed output (if the task had an
 * {@code outputType}), the task description, and the agent role that produced the output.
 *
 * @param rawResponse     the full text response produced by the agent
 * @param parsedOutput    the parsed Java object when the task declared an
 *                        {@code outputType}; {@code null} when no structured output
 *                        was requested
 * @param taskDescription the description of the task that was executed
 * @param agentRole       the role of the agent that produced the output
 */
public record GuardrailOutput(String rawResponse, Object parsedOutput, String taskDescription, String agentRole) {}
