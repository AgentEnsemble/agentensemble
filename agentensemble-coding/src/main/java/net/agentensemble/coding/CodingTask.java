package net.agentensemble.coding;

import java.util.Objects;
import net.agentensemble.Task;

/**
 * Convenience factories for common coding tasks.
 *
 * <p>Each method returns a standard {@link Task} with a pre-filled description and expected
 * output appropriate for the task type. The returned task can be further customized via
 * {@code toBuilder()}.
 *
 * <p>Usage:
 * <pre>
 * Task bugFix = CodingTask.fix("NullPointerException in UserService.getById()");
 * Task feature = CodingTask.implement("Add pagination to /api/users");
 * Task cleanup = CodingTask.refactor("Extract UserRepository interface from UserService");
 * </pre>
 *
 * @see CodingAgent
 * @see CodingEnsemble
 */
public final class CodingTask {

    private CodingTask() {}

    /**
     * Create a bug-fix task.
     *
     * @param bugDescription description of the bug to fix
     * @return a task configured for bug fixing
     * @throws NullPointerException if {@code bugDescription} is null
     */
    public static Task fix(String bugDescription) {
        Objects.requireNonNull(bugDescription, "bugDescription must not be null");
        return Task.builder()
                .description("Fix the following bug: " + bugDescription)
                .expectedOutput("The bug is fixed and all tests pass. Provide a summary of what was changed and why.")
                .build();
    }

    /**
     * Create a feature-implementation task.
     *
     * @param featureDescription description of the feature to implement
     * @return a task configured for feature implementation
     * @throws NullPointerException if {@code featureDescription} is null
     */
    public static Task implement(String featureDescription) {
        Objects.requireNonNull(featureDescription, "featureDescription must not be null");
        return Task.builder()
                .description("Implement the following feature: " + featureDescription)
                .expectedOutput("The feature is implemented with tests. Provide a summary of the implementation.")
                .build();
    }

    /**
     * Create a refactoring task.
     *
     * @param refactoringDescription description of the refactoring to perform
     * @return a task configured for refactoring
     * @throws NullPointerException if {@code refactoringDescription} is null
     */
    public static Task refactor(String refactoringDescription) {
        Objects.requireNonNull(refactoringDescription, "refactoringDescription must not be null");
        return Task.builder()
                .description("Refactor the following: " + refactoringDescription)
                .expectedOutput("The code is refactored with all tests still passing. "
                        + "Provide a summary of the changes made.")
                .build();
    }
}
