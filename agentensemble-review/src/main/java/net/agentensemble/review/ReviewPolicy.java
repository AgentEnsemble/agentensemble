package net.agentensemble.review;

/**
 * Ensemble-level policy that controls when after-execution review gates fire.
 *
 * <p>Set on {@code Ensemble.builder().reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)} to apply
 * a uniform review policy across all tasks. Task-level {@link Review} configurations can
 * override this policy per task:
 *
 * <ul>
 *   <li>{@link Review#required()} forces review on a task regardless of ensemble policy.</li>
 *   <li>{@link Review#skip()} suppresses review on a task regardless of ensemble policy.</li>
 * </ul>
 *
 * <p>The policy only applies to the after-execution gate. Before-execution gates are
 * always task-level opt-in via {@code Task.builder().beforeReview(Review)}.
 *
 * <p>Default: {@link #NEVER} -- no review gates fire unless explicitly requested per task.
 */
public enum ReviewPolicy {

    /**
     * No review gates fire unless the task has an explicit {@code .review(Review.required())}
     * configuration.
     *
     * <p>This is the default.
     */
    NEVER,

    /**
     * The after-execution review gate fires for every task in the ensemble.
     *
     * <p>Tasks that explicitly set {@code .review(Review.skip())} are exempt.
     */
    AFTER_EVERY_TASK,

    /**
     * The after-execution review gate fires only for the final task in the ensemble.
     *
     * <p>The final task may still be exempted with {@code .review(Review.skip())}.
     */
    AFTER_LAST_TASK
}
