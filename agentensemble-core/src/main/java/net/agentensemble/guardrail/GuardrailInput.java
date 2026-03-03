package net.agentensemble.guardrail;

import java.util.List;
import net.agentensemble.task.TaskOutput;

/**
 * The context object passed to an {@link InputGuardrail} before agent execution begins.
 *
 * Carries everything the guardrail needs to decide whether the task should proceed:
 * the task description, expected output, outputs from prior context tasks, and the
 * agent role that will execute the task.
 *
 * @param taskDescription the description of the task about to be executed
 * @param expectedOutput  the expected output as configured on the task
 * @param contextOutputs  outputs from prior tasks declared as context for this task
 *                        (immutable; may be empty)
 * @param agentRole       the role of the agent assigned to execute this task
 */
public record GuardrailInput(
        String taskDescription, String expectedOutput, List<TaskOutput> contextOutputs, String agentRole) {

    /**
     * Defensive copy constructor: ensures contextOutputs is always immutable.
     */
    public GuardrailInput {
        contextOutputs = List.copyOf(contextOutputs);
    }
}
