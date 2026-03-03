package net.agentensemble.guardrail;

/**
 * A pluggable validation hook that runs after an agent produces a response.
 *
 * Implement this functional interface to inspect the raw response text and (when
 * applicable) the parsed structured output, then decide whether the response should
 * be accepted.
 *
 * When the guardrail returns a {@linkplain GuardrailResult#failure(String) failure}
 * result, {@link GuardrailViolationException} is thrown. When structured output
 * parsing was requested ({@code task.outputType} is set), output guardrails run after
 * parsing completes -- the parsed object is available via
 * {@link GuardrailOutput#parsedOutput()}.
 *
 * When multiple output guardrails are configured on a task, they are evaluated in
 * order. The first failure stops evaluation and throws the exception.
 *
 * Example:
 * <pre>
 * OutputGuardrail lengthGuardrail = output -> {
 *     if (output.rawResponse().length() > 5000) {
 *         return GuardrailResult.failure("Response exceeds maximum length of 5000 characters");
 *     }
 *     return GuardrailResult.success();
 * };
 * </pre>
 */
@FunctionalInterface
public interface OutputGuardrail {

    /**
     * Validate the agent output after execution completes.
     *
     * @param output the guardrail output context
     * @return a {@link GuardrailResult} indicating pass or fail
     */
    GuardrailResult validate(GuardrailOutput output);
}
