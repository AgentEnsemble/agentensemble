package net.agentensemble.guardrail;

import net.agentensemble.exception.AgentEnsembleException;

/**
 * Thrown when a guardrail validation check fails, blocking task execution.
 *
 * Carries the guardrail type (INPUT or OUTPUT), the violation message returned
 * by the guardrail, the task description, and the agent role -- providing full
 * diagnostic context at the call site.
 *
 * Input guardrail violations are thrown before any LLM call is made.
 * Output guardrail violations are thrown after the agent response (and any
 * structured output parsing) has completed.
 */
public class GuardrailViolationException extends AgentEnsembleException {

    /**
     * Identifies whether the violation was detected on input (before execution) or
     * output (after execution).
     */
    public enum GuardrailType {
        /** The guardrail ran before the LLM call and blocked execution. */
        INPUT,
        /** The guardrail ran after the LLM response and blocked the result. */
        OUTPUT
    }

    private final GuardrailType guardrailType;
    private final String violationMessage;
    private final String taskDescription;
    private final String agentRole;

    /**
     * Constructs a new {@code GuardrailViolationException}.
     *
     * @param guardrailType    whether the violation was on INPUT or OUTPUT
     * @param violationMessage the reason returned by the failing guardrail
     * @param taskDescription  the description of the task that was blocked
     * @param agentRole        the role of the agent assigned to the task
     */
    public GuardrailViolationException(
            GuardrailType guardrailType, String violationMessage, String taskDescription, String agentRole) {
        super(buildMessage(guardrailType, violationMessage, taskDescription, agentRole));
        this.guardrailType = guardrailType;
        this.violationMessage = violationMessage;
        this.taskDescription = taskDescription;
        this.agentRole = agentRole;
    }

    private static String buildMessage(
            GuardrailType guardrailType, String violationMessage, String taskDescription, String agentRole) {
        return guardrailType
                + " guardrail violation on agent '"
                + agentRole
                + "' task '"
                + taskDescription
                + "': "
                + violationMessage;
    }

    /**
     * Returns whether this violation was detected on the task input or output.
     *
     * @return the guardrail type
     */
    public GuardrailType getGuardrailType() {
        return guardrailType;
    }

    /**
     * Returns the failure reason supplied by the failing guardrail.
     *
     * @return the violation message, never null
     */
    public String getViolationMessage() {
        return violationMessage;
    }

    /**
     * Returns the description of the task that was blocked.
     *
     * @return the task description, never null
     */
    public String getTaskDescription() {
        return taskDescription;
    }

    /**
     * Returns the role of the agent assigned to the blocked task.
     *
     * @return the agent role, never null
     */
    public String getAgentRole() {
        return agentRole;
    }
}
