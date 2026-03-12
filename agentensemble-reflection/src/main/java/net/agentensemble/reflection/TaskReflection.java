package net.agentensemble.reflection;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The result of a task reflection analysis.
 *
 * <p>A {@code TaskReflection} captures improvements identified after examining a task's
 * compile-time definition and the output it produced. It is stored in a
 * {@link ReflectionStore} and injected into the task's prompt on subsequent runs to
 * guide the agent toward better results.
 *
 * <p>Instances are immutable and safe for concurrent read access from multiple threads.
 *
 * @param refinedDescription     an improved version of the original task description,
 *                               addressing clarity gaps or ambiguities identified during
 *                               the reflection analysis
 * @param refinedExpectedOutput  an improved version of the task's expected output
 *                               specification, tightening format requirements that were
 *                               not met in the observed output
 * @param observations           notable patterns, gaps, or issues identified by the
 *                               reflection analysis; may be empty but never null
 * @param suggestions            actionable improvements proposed for future runs;
 *                               may be empty but never null
 * @param reflectedAt            timestamp of the most recent reflection; never null
 * @param runCount               number of times this task has been reflected on;
 *                               always >= 1
 */
public record TaskReflection(
        String refinedDescription,
        String refinedExpectedOutput,
        List<String> observations,
        List<String> suggestions,
        Instant reflectedAt,
        int runCount) {

    /**
     * Compact canonical constructor validating all required fields.
     *
     * @throws NullPointerException     if any field is null
     * @throws IllegalArgumentException if runCount is less than 1
     */
    public TaskReflection {
        Objects.requireNonNull(refinedDescription, "refinedDescription must not be null");
        Objects.requireNonNull(refinedExpectedOutput, "refinedExpectedOutput must not be null");
        Objects.requireNonNull(observations, "observations must not be null");
        Objects.requireNonNull(suggestions, "suggestions must not be null");
        Objects.requireNonNull(reflectedAt, "reflectedAt must not be null");
        if (runCount < 1) {
            throw new IllegalArgumentException("runCount must be >= 1, was: " + runCount);
        }
        observations = List.copyOf(observations);
        suggestions = List.copyOf(suggestions);
    }

    /**
     * Creates a first-run {@code TaskReflection} from the provided analysis components.
     *
     * <p>Sets {@code runCount} to {@code 1} and {@code reflectedAt} to the current instant.
     *
     * @param refinedDescription    improved task description; must not be null
     * @param refinedExpectedOutput improved expected output specification; must not be null
     * @param observations          list of observations; must not be null
     * @param suggestions           list of suggestions; must not be null
     * @return a new {@code TaskReflection} for run 1
     */
    public static TaskReflection ofFirstRun(
            String refinedDescription,
            String refinedExpectedOutput,
            List<String> observations,
            List<String> suggestions) {
        return new TaskReflection(
                refinedDescription, refinedExpectedOutput, observations, suggestions, Instant.now(), 1);
    }

    /**
     * Creates an updated {@code TaskReflection} from a prior reflection, incrementing
     * the run count and refreshing the timestamp.
     *
     * @param refinedDescription    improved task description; must not be null
     * @param refinedExpectedOutput improved expected output specification; must not be null
     * @param observations          updated list of observations; must not be null
     * @param suggestions           updated list of suggestions; must not be null
     * @param prior                 the previous reflection whose run count is incremented;
     *                              must not be null
     * @return a new {@code TaskReflection} with {@code runCount = prior.runCount() + 1}
     */
    public static TaskReflection fromPrior(
            String refinedDescription,
            String refinedExpectedOutput,
            List<String> observations,
            List<String> suggestions,
            TaskReflection prior) {
        Objects.requireNonNull(prior, "prior must not be null");
        return new TaskReflection(
                refinedDescription,
                refinedExpectedOutput,
                observations,
                suggestions,
                Instant.now(),
                prior.runCount() + 1);
    }
}
