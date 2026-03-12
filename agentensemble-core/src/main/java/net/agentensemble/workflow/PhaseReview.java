package net.agentensemble.workflow;

import lombok.Getter;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.review.PhaseReviewDecision;

/**
 * Configuration for a phase-level review gate.
 *
 * <p>When attached to a {@link Phase}, the review gate fires after all tasks in the phase
 * have completed. The review {@link #task} is executed and its output is parsed into a
 * {@link PhaseReviewDecision}. Based on the decision, the phase may be approved, retried
 * with feedback, have a predecessor retried, or rejected.
 *
 * <h2>Review task types</h2>
 *
 * The review task can be any of the three task types the framework supports:
 *
 * <ul>
 *   <li><b>AI review</b> -- task with an agent; the description instructs the LLM to
 *       respond with the text decision format (APPROVE, RETRY: feedback, etc.).</li>
 *   <li><b>Deterministic review</b> -- task with a {@code handler} that programmatically
 *       evaluates the phase output and returns {@link PhaseReviewDecision#toText()} on
 *       the desired decision.</li>
 *   <li><b>Human review</b> -- task with a {@code Review} gate that pauses for console
 *       or custom reviewer input; the reviewer types the decision in text format.</li>
 * </ul>
 *
 * <h2>Quick start</h2>
 *
 * <pre>
 * // AI reviewer
 * Task reviewTask = Task.builder()
 *     .description("Evaluate the research output. "
 *         + "Respond with: APPROVE if sufficient, or "
 *         + "RETRY: &lt;feedback&gt; if it needs improvement.")
 *     .build();
 *
 * Phase research = Phase.builder()
 *     .name("research")
 *     .task(gatherTask)
 *     .task(summarizeTask)
 *     .review(PhaseReview.of(reviewTask))
 *     .build();
 *
 * // Deterministic reviewer
 * Task qualityGate = Task.builder()
 *     .description("Quality check")
 *     .handler(ctx -&gt; {
 *         String output = ctx.contextOutputs().isEmpty()
 *             ? "" : ctx.contextOutputs().getLast().getRaw();
 *         if (output.length() &lt; 200) {
 *             return ToolResult.success(PhaseReviewDecision.retry("Too short").toText());
 *         }
 *         return ToolResult.success(PhaseReviewDecision.approve().toText());
 *     })
 *     .build();
 *
 * Phase research = Phase.builder()
 *     .name("research")
 *     .task(gatherTask)
 *     .review(PhaseReview.of(qualityGate, 3))
 *     .build();
 * </pre>
 *
 * <h2>Downstream phase retry</h2>
 *
 * <p>A review task may return
 * {@link PhaseReviewDecision#retryPredecessor(String, String)} to re-execute a direct
 * predecessor phase that it deems insufficient. The predecessor is re-run with the
 * feedback injected into its tasks, and then the reviewing phase is re-run with the
 * updated predecessor outputs.
 *
 * <pre>
 * Phase writing = Phase.builder()
 *     .name("writing")
 *     .after(research)
 *     .task(draftTask)
 *     .review(PhaseReview.builder()
 *         .task(writingReviewTask)
 *         .maxRetries(2)
 *         .maxPredecessorRetries(1)
 *         .build())
 *     .build();
 * </pre>
 *
 * <h2>Constraints</h2>
 *
 * <ul>
 *   <li>{@code task} must not be null.</li>
 *   <li>{@code maxRetries} must be &gt;= 0. Default: {@value #DEFAULT_MAX_RETRIES}.</li>
 *   <li>{@code maxPredecessorRetries} must be &gt;= 0.
 *       Default: {@value #DEFAULT_MAX_PREDECESSOR_RETRIES}.</li>
 * </ul>
 *
 * @see PhaseReviewDecision
 */
@Getter
public final class PhaseReview {

    /** Default maximum number of self-retry attempts. */
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Default maximum number of predecessor-retry attempts. */
    public static final int DEFAULT_MAX_PREDECESSOR_RETRIES = 2;

    /**
     * The task that evaluates the phase output and returns a {@link PhaseReviewDecision}.
     *
     * <p>The review task receives all outputs from the reviewed phase as prior context
     * (accessible via {@code Task.context()} declarations). Its raw output is parsed by
     * {@link PhaseReviewDecision#parse(String)}.
     */
    private final Task task;

    /**
     * Maximum number of times the phase may be self-retried on a
     * {@link PhaseReviewDecision.Retry} decision.
     *
     * <p>When the retry count is exhausted the last attempt's output is accepted and the
     * pipeline continues. Default: {@value #DEFAULT_MAX_RETRIES}.
     */
    private final int maxRetries;

    /**
     * Maximum number of times a predecessor phase may be retried in response to a
     * {@link PhaseReviewDecision.RetryPredecessor} decision from this phase's review.
     *
     * <p>Each distinct predecessor is tracked independently. When exhausted the reviewing
     * phase continues with the predecessor's last output.
     * Default: {@value #DEFAULT_MAX_PREDECESSOR_RETRIES}.
     */
    private final int maxPredecessorRetries;

    private PhaseReview(Task task, int maxRetries, int maxPredecessorRetries) {
        this.task = task;
        this.maxRetries = maxRetries;
        this.maxPredecessorRetries = maxPredecessorRetries;
    }

    // ========================
    // Static factories
    // ========================

    /**
     * Create a {@code PhaseReview} with the given review task and default retry limits.
     *
     * @param task the review task; must not be null
     * @return a new {@code PhaseReview}
     */
    public static PhaseReview of(Task task) {
        return builder().task(task).build();
    }

    /**
     * Create a {@code PhaseReview} with the given review task and self-retry limit.
     *
     * @param task       the review task; must not be null
     * @param maxRetries maximum self-retries; must be &gt;= 0
     * @return a new {@code PhaseReview}
     */
    public static PhaseReview of(Task task, int maxRetries) {
        return builder().task(task).maxRetries(maxRetries).build();
    }

    /**
     * Return a new builder for constructing a {@code PhaseReview}.
     *
     * @return a new builder instance
     */
    public static PhaseReviewBuilder builder() {
        return new PhaseReviewBuilder();
    }

    // ========================
    // Builder
    // ========================

    /**
     * Builder for {@link PhaseReview}.
     *
     * <p>Use {@link PhaseReview#builder()} to obtain an instance.
     */
    public static final class PhaseReviewBuilder {

        private Task task;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int maxPredecessorRetries = DEFAULT_MAX_PREDECESSOR_RETRIES;

        private PhaseReviewBuilder() {}

        /**
         * Set the review task.
         *
         * @param task the review task; must not be null when {@link #build()} is called
         * @return this builder
         */
        public PhaseReviewBuilder task(Task task) {
            this.task = task;
            return this;
        }

        /**
         * Set the maximum number of self-retries.
         *
         * @param maxRetries must be &gt;= 0; default is {@value PhaseReview#DEFAULT_MAX_RETRIES}
         * @return this builder
         */
        public PhaseReviewBuilder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        /**
         * Set the maximum number of predecessor retries per predecessor phase.
         *
         * @param maxPredecessorRetries must be &gt;= 0;
         *        default is {@value PhaseReview#DEFAULT_MAX_PREDECESSOR_RETRIES}
         * @return this builder
         */
        public PhaseReviewBuilder maxPredecessorRetries(int maxPredecessorRetries) {
            this.maxPredecessorRetries = maxPredecessorRetries;
            return this;
        }

        /**
         * Build and return the {@link PhaseReview}.
         *
         * @return a new {@code PhaseReview}
         * @throws ValidationException if {@code task} is null or retry limits are negative
         */
        public PhaseReview build() {
            if (task == null) {
                throw new ValidationException("PhaseReview task must not be null");
            }
            if (maxRetries < 0) {
                throw new ValidationException("PhaseReview maxRetries must be >= 0, got: " + maxRetries);
            }
            if (maxPredecessorRetries < 0) {
                throw new ValidationException(
                        "PhaseReview maxPredecessorRetries must be >= 0, got: " + maxPredecessorRetries);
            }
            return new PhaseReview(task, maxRetries, maxPredecessorRetries);
        }
    }
}
