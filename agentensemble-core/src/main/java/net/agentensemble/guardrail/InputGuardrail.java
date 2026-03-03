package net.agentensemble.guardrail;

/**
 * A pluggable validation hook that runs before an agent executes a task.
 *
 * Implement this functional interface to inspect the task input (description,
 * expected output, prior context outputs, and agent role) and decide whether
 * execution should proceed.
 *
 * When the guardrail returns a {@linkplain GuardrailResult#failure(String) failure}
 * result, {@link GuardrailViolationException} is thrown immediately -- before any
 * LLM call is made.
 *
 * When multiple input guardrails are configured on a task, they are evaluated in
 * order. The first failure stops evaluation and throws the exception.
 *
 * Example:
 * <pre>
 * InputGuardrail noPiiGuardrail = input -> {
 *     if (containsPersonalInfo(input.taskDescription())) {
 *         return GuardrailResult.failure("Task description contains personal information");
 *     }
 *     return GuardrailResult.success();
 * };
 * </pre>
 */
@FunctionalInterface
public interface InputGuardrail {

    /**
     * Validate the task input before execution begins.
     *
     * @param input the guardrail input context
     * @return a {@link GuardrailResult} indicating pass or fail
     */
    GuardrailResult validate(GuardrailInput input);
}
