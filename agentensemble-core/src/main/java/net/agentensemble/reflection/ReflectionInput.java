package net.agentensemble.reflection;

import java.util.Objects;
import java.util.Optional;
import net.agentensemble.Task;

/**
 * Input bundle provided to a {@link ReflectionStrategy} when performing task reflection.
 *
 * <p>Bundles together everything the strategy needs to produce a {@link TaskReflection}:
 * the original task definition, the output it produced, and any prior reflection
 * stored from previous runs.
 *
 * @param task           the original compile-time task definition; never null
 * @param taskOutput     the raw text output produced by this execution; never null
 * @param priorReflection the reflection stored from the previous run, or empty if this
 *                        is the first execution of this task with reflection enabled
 */
public record ReflectionInput(Task task, String taskOutput, Optional<TaskReflection> priorReflection) {

    /**
     * Compact canonical constructor validating all required fields.
     *
     * @throws NullPointerException if task, taskOutput, or priorReflection is null
     */
    public ReflectionInput {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(taskOutput, "taskOutput must not be null");
        Objects.requireNonNull(priorReflection, "priorReflection must not be null");
    }

    /**
     * Creates a {@code ReflectionInput} for the first execution of a task (no prior reflection).
     *
     * @param task       the original task definition; must not be null
     * @param taskOutput the output produced; must not be null
     * @return a new {@code ReflectionInput} with an empty {@code priorReflection}
     */
    public static ReflectionInput firstRun(Task task, String taskOutput) {
        return new ReflectionInput(task, taskOutput, Optional.empty());
    }

    /**
     * Creates a {@code ReflectionInput} with a prior reflection from a previous run.
     *
     * @param task            the original task definition; must not be null
     * @param taskOutput      the output produced; must not be null
     * @param priorReflection the reflection from the prior run; must not be null
     * @return a new {@code ReflectionInput} with the prior reflection present
     */
    public static ReflectionInput withPrior(Task task, String taskOutput, TaskReflection priorReflection) {
        Objects.requireNonNull(priorReflection, "priorReflection must not be null");
        return new ReflectionInput(task, taskOutput, Optional.of(priorReflection));
    }

    /**
     * Returns {@code true} if no prior reflection exists for this task.
     *
     * @return true if this is the first reflection run
     */
    public boolean isFirstRun() {
        return priorReflection.isEmpty();
    }
}
