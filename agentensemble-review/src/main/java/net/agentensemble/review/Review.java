package net.agentensemble.review;

import java.time.Duration;

/**
 * Task-level review gate configuration.
 *
 * <p>Attach a {@code Review} to a task to control whether and how a review gate fires
 * before or after execution:
 *
 * <pre>
 * Task task = Task.builder()
 *     .description("Write a blog post about AI trends")
 *     .expectedOutput("A 500-word blog post")
 *     .review(Review.required())        // gate fires after execution
 *     .beforeReview(Review.required())  // gate fires before execution
 *     .build();
 * </pre>
 *
 * <p>Use the static factories for the common cases:
 * <ul>
 *   <li>{@link #required()} -- always fire the review gate; uses the ensemble-level handler</li>
 *   <li>{@link #required(String)} -- always fire with a custom display message</li>
 *   <li>{@link #skip()} -- never fire the gate, even if the ensemble policy would</li>
 * </ul>
 *
 * <p>Use {@link #builder()} for fine-grained control over timeout and timeout action:
 * <pre>
 * Review.builder()
 *     .timeout(Duration.ofMinutes(10))
 *     .onTimeout(OnTimeoutAction.CONTINUE)
 *     .build();
 * </pre>
 */
public final class Review {

    /** Default timeout applied when none is explicitly configured. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Default on-timeout action: stop the pipeline so unreviewed output does not
     * propagate downstream.
     */
    public static final OnTimeoutAction DEFAULT_ON_TIMEOUT = OnTimeoutAction.EXIT_EARLY;

    private final boolean skip;
    private final String prompt;
    private final Duration timeout;
    private final OnTimeoutAction onTimeoutAction;

    private Review(boolean skip, String prompt, Duration timeout, OnTimeoutAction onTimeoutAction) {
        this.skip = skip;
        this.prompt = prompt;
        this.timeout = timeout;
        this.onTimeoutAction = onTimeoutAction;
    }

    // ========================
    // Static factories
    // ========================

    /**
     * Create a {@code Review} configuration that always fires the review gate using the
     * ensemble-level {@link ReviewHandler}.
     *
     * <p>Timeout defaults to {@link #DEFAULT_TIMEOUT} (5 minutes). On timeout,
     * {@link OnTimeoutAction#EXIT_EARLY} is applied.
     *
     * @return a required Review configuration
     */
    public static Review required() {
        return new Review(false, null, DEFAULT_TIMEOUT, DEFAULT_ON_TIMEOUT);
    }

    /**
     * Create a {@code Review} configuration that always fires the review gate with a
     * custom display message shown to the reviewer.
     *
     * @param prompt the message to show above the standard task information; must not be null
     * @return a required Review configuration with a custom prompt
     */
    public static Review required(String prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null");
        }
        return new Review(false, prompt, DEFAULT_TIMEOUT, DEFAULT_ON_TIMEOUT);
    }

    /**
     * Create a {@code Review} configuration that suppresses the review gate for this task,
     * even if the ensemble-level {@link ReviewPolicy} would otherwise fire it.
     *
     * @return a skip Review configuration
     */
    public static Review skip() {
        return new Review(true, null, null, null);
    }

    /**
     * Return a builder for fine-grained Review configuration.
     *
     * @return a new ReviewBuilder
     */
    public static ReviewBuilder builder() {
        return new ReviewBuilder();
    }

    // ========================
    // Accessors
    // ========================

    /**
     * Whether this configuration suppresses the review gate.
     *
     * @return {@code true} when the gate should be skipped regardless of ensemble policy
     */
    public boolean isSkip() {
        return skip;
    }

    /**
     * Whether this configuration requires the review gate to fire.
     *
     * @return {@code true} when the gate should always fire
     */
    public boolean isRequired() {
        return !skip;
    }

    /**
     * The optional custom display message shown to the reviewer.
     *
     * @return the custom prompt, or {@code null} when none was configured
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * How long to wait for a human response before executing {@link #getOnTimeoutAction()}.
     *
     * @return the timeout; never null when this is a required review ({@link #isSkip()} is false)
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * The action to execute when the review gate times out.
     *
     * @return the on-timeout action; never null when this is a required review
     */
    public OnTimeoutAction getOnTimeoutAction() {
        return onTimeoutAction;
    }

    // ========================
    // Builder
    // ========================

    /**
     * Builder for fine-grained {@link Review} configuration.
     */
    public static final class ReviewBuilder {

        private String prompt;
        private Duration timeout = DEFAULT_TIMEOUT;
        private OnTimeoutAction onTimeoutAction = DEFAULT_ON_TIMEOUT;

        private ReviewBuilder() {}

        /**
         * Set a custom display message shown to the reviewer above the standard task
         * information.
         *
         * @param prompt the message to show; must not be null
         * @return this builder
         */
        public ReviewBuilder prompt(String prompt) {
            if (prompt == null) {
                throw new IllegalArgumentException("prompt must not be null");
            }
            this.prompt = prompt;
            return this;
        }

        /**
         * Set how long to wait for a human response before executing the timeout action.
         *
         * @param timeout the timeout duration; must not be null or non-positive
         * @return this builder
         */
        public ReviewBuilder timeout(Duration timeout) {
            if (timeout == null) {
                throw new IllegalArgumentException("timeout must not be null");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * Set the action to take when the review times out.
         *
         * @param action the on-timeout action; must not be null
         * @return this builder
         */
        public ReviewBuilder onTimeout(OnTimeoutAction action) {
            if (action == null) {
                throw new IllegalArgumentException("onTimeout action must not be null");
            }
            this.onTimeoutAction = action;
            return this;
        }

        /**
         * Build and return the {@link Review} configuration.
         *
         * @return a new required Review
         */
        public Review build() {
            return new Review(false, prompt, timeout, onTimeoutAction);
        }
    }
}
