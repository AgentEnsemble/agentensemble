package net.agentensemble.reflection;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.Task;
import net.agentensemble.callback.TaskReflectedEvent;
import net.agentensemble.execution.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the post-completion reflection step for a task.
 *
 * <p>Reflection runs after all reviews pass and the task output is accepted. It:
 * <ol>
 *   <li>Retrieves the prior reflection for this task from the {@link ReflectionStore}</li>
 *   <li>Builds a {@link ReflectionInput} containing the task definition, output, and prior</li>
 *   <li>Invokes the configured or default {@link ReflectionStrategy}</li>
 *   <li>Stores the produced {@link TaskReflection} in the store</li>
 *   <li>Fires a {@link TaskReflectedEvent} to all registered listeners</li>
 * </ol>
 *
 * <p>Reflection failures are non-fatal. Any exception during the reflection step is caught
 * and logged; the task output is never affected.
 *
 * <p>Model resolution order for the default {@link LlmReflectionStrategy}:
 * <ol>
 *   <li>{@link ReflectionConfig#model()} — model configured explicitly on the reflection config</li>
 *   <li>{@code task.getChatLanguageModel()} — task-level model override</li>
 *   <li>{@code ensembleModel} — ensemble-level model</li>
 * </ol>
 */
public final class TaskReflector {

    private static final Logger log = LoggerFactory.getLogger(TaskReflector.class);

    private TaskReflector() {}

    /**
     * Run the reflection step for a task, if reflection is configured.
     *
     * <p>This method is a no-op if:
     * <ul>
     *   <li>{@code task.getReflectionConfig()} is null (reflection not enabled on this task)</li>
     *   <li>The reflection store is null and no model is available to create a fallback</li>
     * </ul>
     *
     * @param task           the task that was executed; must not be null
     * @param rawOutput      the raw text output produced by the task; must not be null
     * @param ensembleModel  the ensemble-level model for fallback; may be null
     * @param context        the execution context; must not be null
     */
    public static void reflect(Task task, String rawOutput, ChatModel ensembleModel, ExecutionContext context) {

        ReflectionConfig reflectionConfig = task.getReflectionConfig();
        if (reflectionConfig == null) {
            return;
        }

        ReflectionStore store = resolveStore(context, task);
        if (store == null) {
            return;
        }

        try {
            String taskIdentity = TaskIdentity.of(task);

            // Load prior reflection
            java.util.Optional<TaskReflection> priorOpt = store.retrieve(taskIdentity);
            boolean isFirstReflection = priorOpt.isEmpty();

            // Build input
            ReflectionInput input = priorOpt.map(prior -> ReflectionInput.withPrior(task, rawOutput, prior))
                    .orElseGet(() -> ReflectionInput.firstRun(task, rawOutput));

            // Resolve strategy
            ReflectionStrategy strategy = resolveStrategy(reflectionConfig, task, ensembleModel);
            if (strategy == null) {
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Reflection is enabled on task '{}' but no LLM model is available. "
                                    + "Configure a model on the Ensemble or ReflectionConfig.",
                            abbreviate(task.getDescription(), 60));
                }
                return;
            }

            // Run reflection
            TaskReflection reflection = strategy.reflect(input);

            // Store
            store.store(taskIdentity, reflection);

            if (log.isDebugEnabled()) {
                log.debug(
                        "Reflection stored for task '{}' (run {})",
                        abbreviate(task.getDescription(), 60),
                        reflection.runCount());
            }

            // Fire event
            context.fireTaskReflected(new TaskReflectedEvent(task.getDescription(), reflection, isFirstReflection));

        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Reflection failed for task '{}': {}",
                        abbreviate(task.getDescription(), 60),
                        e.getMessage(),
                        e);
            }
        }
    }

    /**
     * Resolves the {@link ReflectionStore} to use from the execution context.
     *
     * <p>Returns {@code null} (skipping reflection) when no store is present. A store is
     * auto-provisioned by {@link net.agentensemble.Ensemble} at run time when any task has
     * reflection enabled but no explicit store is configured, so this path is only hit when
     * {@code TaskReflector.reflect()} is invoked outside of the standard Ensemble lifecycle
     * (e.g., in tests that build an {@link ExecutionContext} manually without a store).
     */
    private static ReflectionStore resolveStore(ExecutionContext context, Task task) {
        ReflectionStore store = context.reflectionStore();
        if (store == null) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Task '{}' has reflection enabled but no ReflectionStore is available in the "
                                + "ExecutionContext. Reflection will be skipped for this task. "
                                + "Configure a store via Ensemble.builder().reflectionStore(...).",
                        abbreviate(task.getDescription(), 60));
            }
            return null;
        }
        return store;
    }

    /**
     * Resolves the strategy to use: custom strategy from config takes priority,
     * otherwise creates a {@link LlmReflectionStrategy} using the resolved model.
     */
    private static ReflectionStrategy resolveStrategy(ReflectionConfig config, Task task, ChatModel ensembleModel) {
        if (config.strategy() != null) {
            return config.strategy();
        }
        ChatModel model = resolveModel(config, task, ensembleModel);
        if (model == null) {
            return null;
        }
        return new LlmReflectionStrategy(model);
    }

    /**
     * Resolves the model for the LLM reflection call in priority order:
     * ReflectionConfig.model > task.chatLanguageModel > ensembleModel
     */
    private static ChatModel resolveModel(ReflectionConfig config, Task task, ChatModel ensembleModel) {
        if (config.model() != null) {
            return config.model();
        }
        if (task.getChatLanguageModel() != null) {
            return task.getChatLanguageModel();
        }
        return ensembleModel;
    }

    private static String abbreviate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
