package net.agentensemble.memory;

import lombok.Builder;
import lombok.Getter;
import net.agentensemble.exception.ValidationException;

/**
 * Memory configuration for an {@code Ensemble}.
 *
 * Enables one or more memory types that are applied during ensemble execution.
 * At least one memory type must be enabled when an {@code EnsembleMemory} is
 * configured. All fields are optional individually; the builder validates
 * that at least one type is active.
 *
 * Memory types:
 * <ul>
 *   <li><strong>Short-term</strong>: Accumulates all task outputs within a
 *       single ensemble run. Subsequent agents receive the full run history
 *       in their prompts, removing the need to declare explicit task
 *       {@code context} dependencies.</li>
 *   <li><strong>Long-term</strong>: Persists task outputs across ensemble runs
 *       using a vector store. Before each task, relevant past memories are
 *       retrieved by semantic similarity and injected into the agent prompt.</li>
 *   <li><strong>Entity memory</strong>: A key-value store of known facts about
 *       named entities. All facts are injected into every agent prompt so
 *       agents share consistent knowledge about referenced entities.</li>
 * </ul>
 *
 * Example (all three memory types):
 * <pre>
 * EntityMemory entities = new InMemoryEntityMemory();
 * entities.put("Acme Corp", "A SaaS company founded in 2015");
 *
 * EnsembleMemory memory = EnsembleMemory.builder()
 *     .shortTerm(true)
 *     .longTerm(new EmbeddingStoreLongTermMemory(embeddingStore, embeddingModel))
 *     .entityMemory(entities)
 *     .build();
 *
 * EnsembleOutput result = Ensemble.builder()
 *     .agents(...)
 *     .tasks(...)
 *     .memory(memory)
 *     .build()
 *     .run();
 * </pre>
 */
@Builder
@Getter
public class EnsembleMemory {

    /**
     * Whether short-term memory is enabled.
     * When true, all task outputs from the current run are injected into
     * subsequent agents' prompts.
     * Default: false.
     */
    private final boolean shortTerm;

    /**
     * Long-term memory implementation.
     * When non-null, task outputs are stored after execution and relevant
     * past memories are retrieved and injected before each task.
     * Default: null (disabled).
     */
    private final LongTermMemory longTerm;

    /**
     * Entity memory store.
     * When non-null, all stored entity facts are injected into every
     * agent prompt.
     * Default: null (disabled).
     */
    private final EntityMemory entityMemory;

    /**
     * Maximum number of long-term memory entries to retrieve per task.
     * Only relevant when {@code longTerm} is configured.
     * Default: 5.
     */
    private final int longTermMaxResults;

    /**
     * Custom builder that sets defaults and validates the configuration.
     *
     * Field initializers define the default values. Lombok respects these
     * when generating builder methods, avoiding static context conflicts.
     */
    public static class EnsembleMemoryBuilder {

        // Default values -- field initializers, not @Builder.Default
        private boolean shortTerm = false;
        private LongTermMemory longTerm = null;
        private EntityMemory entityMemory = null;
        private int longTermMaxResults = 5;

        public EnsembleMemory build() {
            boolean anyEnabled = shortTerm || longTerm != null || entityMemory != null;
            if (!anyEnabled) {
                throw new ValidationException("EnsembleMemory must have at least one memory type enabled: "
                        + "shortTerm=true, longTerm, or entityMemory");
            }
            // longTermMaxResults is only meaningful when long-term memory is configured
            if (longTerm != null && longTermMaxResults <= 0) {
                throw new ValidationException(
                        "EnsembleMemory longTermMaxResults must be > 0, got: " + longTermMaxResults);
            }
            return new EnsembleMemory(shortTerm, longTerm, entityMemory, longTermMaxResults);
        }
    }
}
