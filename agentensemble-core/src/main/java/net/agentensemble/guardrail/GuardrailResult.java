package net.agentensemble.guardrail;

import java.util.Objects;

/**
 * The result of a guardrail validation check.
 *
 * Instances are created via the factory methods {@link #success()} and
 * {@link #failure(String)}.
 *
 * A successful result carries an empty message. A failure result carries a
 * non-null reason string that is included in the
 * {@link GuardrailViolationException} message when the guardrail blocks execution.
 */
public final class GuardrailResult {

    private static final GuardrailResult SUCCESS = new GuardrailResult(true, "");

    private final boolean success;
    private final String message;

    private GuardrailResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * Returns a result indicating the guardrail passed.
     *
     * @return a successful guardrail result
     */
    public static GuardrailResult success() {
        return SUCCESS;
    }

    /**
     * Returns a result indicating the guardrail blocked execution.
     *
     * @param reason a human-readable explanation of why the guardrail failed;
     *               must not be null
     * @return a failure guardrail result carrying the given reason
     * @throws NullPointerException if reason is null
     */
    public static GuardrailResult failure(String reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        return new GuardrailResult(false, reason);
    }

    /**
     * Returns {@code true} if the guardrail passed.
     *
     * @return true when the check succeeded, false when it failed
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the failure reason, or an empty string when the result is a success.
     *
     * @return the failure message, never null
     */
    public String getMessage() {
        return message;
    }
}
