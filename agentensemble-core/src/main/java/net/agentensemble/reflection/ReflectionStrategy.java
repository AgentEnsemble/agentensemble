package net.agentensemble.reflection;

/**
 * SPI for performing task reflection analysis.
 *
 * <p>A {@code ReflectionStrategy} examines a task's definition and its execution output,
 * then produces a {@link TaskReflection} containing improvements to the task's instructions
 * for future runs.
 *
 * <p>The default implementation {@code LlmReflectionStrategy} uses an LLM call to produce
 * the analysis. Custom implementations may use any approach: rule-based analysis, comparison
 * against known-good outputs, domain-specific heuristics, or external evaluation services.
 *
 * <h2>Custom Strategy Example</h2>
 * <pre>
 * public class MyReflectionStrategy implements ReflectionStrategy {
 *     {@literal @}Override
 *     public TaskReflection reflect(ReflectionInput input) {
 *         String improved = analyzeWithMyLogic(
 *             input.task().getDescription(),
 *             input.taskOutput()
 *         );
 *         return TaskReflection.ofFirstRun(
 *             improved,
 *             input.task().getExpectedOutput(),
 *             List.of("Custom analysis applied"),
 *             List.of()
 *         );
 *     }
 * }
 *
 * Task.builder()
 *     .description("...")
 *     .reflect(ReflectionConfig.builder()
 *         .strategy(new MyReflectionStrategy())
 *         .build())
 *     .build();
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * Implementations are invoked once per task execution on a dedicated virtual thread.
 * Implementations should be stateless or internally synchronized.
 */
@FunctionalInterface
public interface ReflectionStrategy {

    /**
     * Perform reflection analysis on the given input and produce improvement notes.
     *
     * @param input the task definition, execution output, and prior reflection; never null
     * @return a {@link TaskReflection} containing the analysis results; must not be null
     */
    TaskReflection reflect(ReflectionInput input);
}
