package net.agentensemble.review;

import java.util.Locale;
import java.util.Objects;

/**
 * The outcome of a phase review task.
 *
 * <p>Returned by the review task that executes after a phase completes. The executor reads
 * the review task's raw output and parses it into one of the four decisions below. For AI
 * review tasks, the task description should instruct the LLM to respond in the text format
 * described by {@link #toText()}. For deterministic handlers, return {@code toText()} on
 * the desired decision. For human review tasks, the human types the response in the same
 * format.
 *
 * <h2>Text format</h2>
 *
 * <ul>
 *   <li>{@code APPROVE} -- accept the phase output and unlock downstream phases.</li>
 *   <li>{@code RETRY: <feedback>} -- re-execute the phase with the given feedback
 *       injected into each task prompt as a {@code ## Revision Instructions} section.</li>
 *   <li>{@code RETRY_PREDECESSOR <phaseName>: <feedback>} -- re-execute a named direct
 *       predecessor of the reviewing phase with the given feedback, then re-execute the
 *       reviewing phase with the refreshed outputs.</li>
 *   <li>{@code REJECT: <reason>} -- fail the phase and skip all downstream phases.</li>
 * </ul>
 *
 * <h2>Quick start</h2>
 *
 * <pre>
 * // AI reviewer task (instructs LLM to respond in APPROVE / RETRY format)
 * Task reviewTask = Task.builder()
 *     .description("Evaluate the research output. "
 *         + "If sufficient, respond with: APPROVE\n"
 *         + "If insufficient, respond with: RETRY: &lt;specific feedback on what to improve&gt;")
 *     .build();
 *
 * // Deterministic reviewer task
 * Task reviewTask = Task.builder()
 *     .description("Quality gate")
 *     .handler(ctx -&gt; {
 *         String output = ctx.contextOutputs().isEmpty() ? "" : ctx.contextOutputs().getLast().getRaw();
 *         if (output.length() &lt; 500) {
 *             return ToolResult.success(PhaseReviewDecision.retry("Output too short.").toText());
 *         }
 *         return ToolResult.success(PhaseReviewDecision.approve().toText());
 *     })
 *     .build();
 * </pre>
 *
 */
public sealed interface PhaseReviewDecision
        permits PhaseReviewDecision.Approve,
                PhaseReviewDecision.Retry,
                PhaseReviewDecision.RetryPredecessor,
                PhaseReviewDecision.Reject {

    // ========================
    // Static factories
    // ========================

    /**
     * Accept the phase output and proceed to downstream phases.
     *
     * @return the approve decision
     */
    static Approve approve() {
        return new Approve();
    }

    /**
     * Re-execute the phase with the given reviewer feedback.
     *
     * <p>The feedback is injected into each task prompt as a
     * {@code ## Revision Instructions} section so the LLM can improve its output.
     *
     * @param feedback specific instructions for improvement; must not be null
     * @return the retry decision
     */
    static Retry retry(String feedback) {
        return new Retry(feedback);
    }

    /**
     * Re-execute a direct predecessor phase with feedback, then re-execute the current phase.
     *
     * <p>Use this when the current phase determines that a predecessor phase's output was
     * insufficient, rather than its own output.
     *
     * @param phaseName the name of the predecessor phase (see {@code Phase.getName()})
     *                  to retry; must be a direct predecessor of the reviewing phase
     * @param feedback  specific instructions for the predecessor phase; must not be null
     * @return the retry-predecessor decision
     */
    static RetryPredecessor retryPredecessor(String phaseName, String feedback) {
        return new RetryPredecessor(phaseName, feedback);
    }

    /**
     * Fail the phase and skip all downstream phases.
     *
     * @param reason human-readable explanation of the rejection; must not be null
     * @return the reject decision
     */
    static Reject reject(String reason) {
        return new Reject(reason);
    }

    // ========================
    // Instance operations
    // ========================

    /**
     * Serialise this decision to its canonical text representation.
     *
     * <p>Deterministic task handlers should return this string as their output so the
     * framework can parse it back via {@link #parse(String)}.
     *
     * @return the text representation
     */
    default String toText() {
        return switch (this) {
            case Approve ignored -> "APPROVE";
            case Retry r -> "RETRY: " + r.feedback();
            case RetryPredecessor rp -> "RETRY_PREDECESSOR " + rp.phaseName() + ": " + rp.feedback();
            case Reject rej -> "REJECT: " + rej.reason();
        };
    }

    // ========================
    // Text parsing
    // ========================

    /**
     * Parse a text string into a {@link PhaseReviewDecision}.
     *
     * <p>Recognised patterns (case-insensitive):
     * <ul>
     *   <li>{@code APPROVE} -- returns {@link Approve}</li>
     *   <li>{@code RETRY}: feedback -- returns {@link Retry}</li>
     *   <li>{@code RETRY_PREDECESSOR} phaseName: feedback -- returns {@link RetryPredecessor}</li>
     *   <li>{@code REJECT}: reason -- returns {@link Reject}</li>
     * </ul>
     *
     * <p>If the text is {@code null}, blank, or does not match any known pattern, returns
     * {@link Approve} and logs a warning. This prevents a malformed LLM response from
     * inadvertently blocking pipeline execution.
     *
     * @param text the raw review task output to parse; may be null
     * @return the parsed decision; never null
     */
    static PhaseReviewDecision parse(String text) {
        if (text == null || text.isBlank()) {
            return new Approve();
        }
        String trimmed = text.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);

        if (upper.startsWith("RETRY_PREDECESSOR ")) {
            String rest = trimmed.substring("RETRY_PREDECESSOR ".length()).trim();
            int colonIdx = rest.indexOf(':');
            if (colonIdx > 0) {
                String phaseName = rest.substring(0, colonIdx).trim();
                String feedback = rest.substring(colonIdx + 1).trim();
                return retryPredecessor(phaseName, feedback);
            }
            // No colon: treat entire rest as phase name with empty feedback
            return retryPredecessor(rest, "");
        }

        if (upper.startsWith("RETRY")) {
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0) {
                return retry(trimmed.substring(colonIdx + 1).trim());
            }
            return retry("");
        }

        if (upper.startsWith("REJECT")) {
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx > 0) {
                return reject(trimmed.substring(colonIdx + 1).trim());
            }
            return reject("");
        }

        // APPROVE or unrecognised input -- treat as approval
        return new Approve();
    }

    // ========================
    // Permitted record implementations
    // ========================

    /**
     * Accept the phase output and proceed.
     *
     * <p>Created via {@link PhaseReviewDecision#approve()}.
     */
    record Approve() implements PhaseReviewDecision {}

    /**
     * Re-execute this phase with the given feedback.
     *
     * <p>Created via {@link PhaseReviewDecision#retry(String)}.
     */
    record Retry(String feedback) implements PhaseReviewDecision {
        /** Validates that {@code feedback} is not null. */
        public Retry {
            Objects.requireNonNull(feedback, "PhaseReviewDecision.Retry: feedback must not be null");
        }
    }

    /**
     * Re-execute a named direct predecessor phase with the given feedback, then
     * re-execute this phase.
     *
     * <p>Created via {@link PhaseReviewDecision#retryPredecessor(String, String)}.
     */
    record RetryPredecessor(String phaseName, String feedback) implements PhaseReviewDecision {
        /** Validates that {@code phaseName} and {@code feedback} are not null. */
        public RetryPredecessor {
            Objects.requireNonNull(phaseName, "PhaseReviewDecision.RetryPredecessor: phaseName must not be null");
            Objects.requireNonNull(feedback, "PhaseReviewDecision.RetryPredecessor: feedback must not be null");
        }
    }

    /**
     * Fail the phase and skip all downstream phases.
     *
     * <p>Created via {@link PhaseReviewDecision#reject(String)}.
     */
    record Reject(String reason) implements PhaseReviewDecision {
        /** Validates that {@code reason} is not null. */
        public Reject {
            Objects.requireNonNull(reason, "PhaseReviewDecision.Reject: reason must not be null");
        }
    }
}
