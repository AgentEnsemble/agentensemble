package net.agentensemble.workflow;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.review.PhaseReviewDecision;

/**
 * A named group of tasks that forms a logical workstream within an ensemble.
 *
 * <p>Phases declare dependencies on each other via {@link PhaseBuilder#after(Phase...)}. A phase
 * with no {@code after()} declarations is a <em>root phase</em> and starts immediately when the
 * ensemble run begins. Independent phases execute in parallel (each on its own virtual thread); a
 * phase only starts once all of its declared predecessors have completed.
 *
 * <p>Within each phase, tasks execute according to that phase's {@link #workflow} override, or the
 * ensemble-level workflow when no override is set.
 *
 * <p>An optional {@link PhaseReview} gate fires after all tasks in the phase complete. The review
 * task evaluates the phase outputs and returns a {@link PhaseReviewDecision}. Based on the
 * decision, the phase may be approved (successors unlocked), retried with feedback, have a
 * predecessor retried, or rejected (pipeline stopped).
 *
 * <h2>Quick start</h2>
 *
 * <pre>
 * // Static factory -- no dependencies, no workflow override, no review
 * Phase research = Phase.of("research", gatherTask, summarizeTask);
 *
 * // Full builder -- with dependency, per-phase workflow, and review gate
 * Phase writing = Phase.builder()
 *     .name("writing")
 *     .after(research)
 *     .task(outlineTask)
 *     .task(draftTask)
 *     .workflow(Workflow.SEQUENTIAL)
 *     .review(PhaseReview.of(writingReviewTask))
 *     .build();
 *
 * // Register phases on the ensemble
 * Ensemble.builder()
 *     .chatLanguageModel(llm)
 *     .phase(research)
 *     .phase(writing)
 *     .build()
 *     .run();
 * </pre>
 *
 * <h2>Constraints</h2>
 *
 * <ul>
 *   <li>{@code name} must not be null or blank.</li>
 *   <li>At least one task must be provided.</li>
 *   <li>{@code workflow} must not be {@link Workflow#HIERARCHICAL} (not supported per-phase).</li>
 *   <li>Phase dependencies must form a DAG (no cycles); validated at
 *       {@code Ensemble.build()} time.</li>
 * </ul>
 *
 * @see PhaseReview
 * @see PhaseReviewDecision
 */
@Builder
@Getter
public class Phase {

    /**
     * Unique name within the ensemble. Used in logs, traces, and
     * {@code EnsembleOutput.getPhaseOutputs()} map keys.
     */
    private final String name;

    /**
     * Tasks to execute within this phase. At least one task is required.
     * Tasks execute according to the phase's (or ensemble-level) workflow strategy.
     */
    @Singular
    private final List<Task> tasks;

    /**
     * Optional workflow strategy for internal task execution.
     *
     * <p>When {@code null} (default), the ensemble-level workflow is used.
     * {@link Workflow#HIERARCHICAL} is not permitted at the phase level.
     */
    private final Workflow workflow;

    /**
     * Predecessor phases. This phase will not start until all declared predecessors have
     * completed successfully. A phase with no predecessors is a root phase and starts immediately.
     *
     * <p>Use the builder's {@code after(Phase...)} varargs method to declare predecessors:
     * <pre>
     * Phase.builder().name("report").after(market, technical).task(reportTask).build();
     * </pre>
     */
    private final List<Phase> after;

    /**
     * Optional review gate that fires after all tasks in this phase complete.
     *
     * <p>When set, the review task is executed with this phase's task outputs available as
     * prior context. The review task's output is parsed into a {@link PhaseReviewDecision}:
     *
     * <ul>
     *   <li>{@link PhaseReviewDecision.Approve} -- unlock downstream phases.</li>
     *   <li>{@link PhaseReviewDecision.Retry} -- re-execute this phase with the feedback
     *       injected into each task prompt as a {@code ## Revision Instructions} section.</li>
     *   <li>{@link PhaseReviewDecision.RetryPredecessor} -- re-execute a named direct
     *       predecessor phase with feedback, then re-execute this phase.</li>
     *   <li>{@link PhaseReviewDecision.Reject} -- fail this phase and skip all successors.</li>
     * </ul>
     *
     * <p>Default: null (no review gate; phase output accepted immediately).
     *
     * @see PhaseReview
     */
    private final PhaseReview review;

    // ========================
    // Static factories
    // ========================

    /**
     * Create a simple phase with the given name and tasks.
     * No workflow override, no dependencies.
     *
     * @param name  unique name within the ensemble; must not be null or blank
     * @param tasks tasks to execute; at least one required
     * @return the built phase
     * @throws ValidationException if name is blank or tasks is empty
     */
    public static Phase of(String name, Task... tasks) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Phase name must not be null or blank");
        }
        if (tasks == null || tasks.length == 0) {
            throw new ValidationException("Phase '" + name + "' must contain at least one task");
        }
        PhaseBuilder builder = Phase.builder().name(name);
        for (Task t : tasks) {
            builder.task(t);
        }
        return builder.build();
    }

    /**
     * Create a simple phase with the given name and task list.
     * No workflow override, no dependencies.
     *
     * @param name  unique name within the ensemble; must not be null or blank
     * @param tasks tasks to execute; must not be null or empty
     * @return the built phase
     * @throws ValidationException if name is blank or tasks is empty
     */
    public static Phase of(String name, List<Task> tasks) {
        if (name == null || name.isBlank()) {
            throw new ValidationException("Phase name must not be null or blank");
        }
        if (tasks == null || tasks.isEmpty()) {
            throw new ValidationException("Phase '" + name + "' must contain at least one task");
        }
        PhaseBuilder builder = Phase.builder().name(name);
        for (Task t : tasks) {
            builder.task(t);
        }
        return builder.build();
    }

    // ========================
    // Custom builder
    // ========================

    /**
     * Custom builder that adds validation and predecessor-declaration convenience methods.
     *
     * <p>The Lombok-generated {@code task(Task)} singular method adds one task at a time:
     * <pre>
     * Phase.builder()
     *     .name("research")
     *     .task(gatherTask)
     *     .task(summarizeTask)
     *     .build();
     * </pre>
     *
     * <p>Predecessor phases are declared using {@code after()}:
     * <pre>
     * Phase.builder()
     *     .name("report")
     *     .after(marketPhase, technicalPhase)
     *     .task(reportTask)
     *     .build();
     * </pre>
     */
    public static class PhaseBuilder {

        // The `after` field is NOT @Singular so that we can provide both
        // after(Phase) and after(Phase...) without conflicting with Lombok-generated code.
        private List<Phase> after = new ArrayList<>();

        /**
         * Declare a single predecessor phase.
         * This phase will not start until the predecessor has completed.
         *
         * @param phase predecessor phase; null is ignored
         * @return this builder
         */
        public PhaseBuilder after(Phase phase) {
            if (phase != null) {
                this.after.add(phase);
            }
            return this;
        }

        /**
         * Declare one or more predecessor phases using varargs.
         * This phase will not start until all declared predecessors have completed.
         *
         * @param phases predecessor phases; null elements are ignored
         * @return this builder
         */
        public PhaseBuilder after(Phase... phases) {
            if (phases != null) {
                for (Phase p : phases) {
                    if (p != null) {
                        this.after.add(p);
                    }
                }
            }
            return this;
        }

        public Phase build() {
            // Validate name
            if (name == null || name.isBlank()) {
                throw new ValidationException("Phase name must not be null or blank");
            }

            // Validate tasks -- Lombok @Singular initialises to an empty ArrayList
            if (tasks == null || tasks.isEmpty()) {
                throw new ValidationException("Phase '" + name + "' must contain at least one task");
            }

            // Validate workflow -- HIERARCHICAL is not supported per-phase
            if (workflow == Workflow.HIERARCHICAL) {
                throw new ValidationException(
                        "Phase '" + name + "': Workflow.HIERARCHICAL is not supported at the phase level. "
                                + "Use HIERARCHICAL at the ensemble level (without phases) for hierarchical delegation.");
            }

            List<Task> immutableTasks = List.copyOf(tasks);
            List<Phase> immutableAfter = List.copyOf(after);
            return new Phase(name, immutableTasks, workflow, immutableAfter, review);
        }
    }
}
