package net.agentensemble.workflow;

import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;

/**
 * Builds the meta-prompts used by the Manager agent in a hierarchical workflow.
 *
 * <p><strong>Deprecated.</strong> Use {@link DefaultManagerPromptStrategy#DEFAULT} (or a custom
 * {@link ManagerPromptStrategy}) registered via {@code Ensemble.Builder.managerPromptStrategy()}
 * instead. This class will be removed in a future release.
 *
 * <p>Both static methods now delegate to {@link DefaultManagerPromptStrategy#DEFAULT} and will
 * produce identical output.
 *
 * @deprecated Use {@link ManagerPromptStrategy} and {@link DefaultManagerPromptStrategy} instead.
 */
@Deprecated(forRemoval = true)
public final class ManagerPromptBuilder {

    private ManagerPromptBuilder() {
        // Utility class -- not instantiable
    }

    /**
     * Build the background text listing all available worker agents.
     *
     * @param workers the worker agents available for delegation
     * @return formatted string listing agents with roles, goals, and backgrounds
     * @deprecated Use {@link DefaultManagerPromptStrategy#DEFAULT}.{@link
     *     DefaultManagerPromptStrategy#buildSystemPrompt(ManagerPromptContext)} instead.
     */
    @Deprecated(forRemoval = true)
    public static String buildBackground(List<Agent> workers) {
        ManagerPromptContext ctx = new ManagerPromptContext(workers, List.of(), List.of(), null);
        return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
    }

    /**
     * Build the task description text listing all tasks to complete.
     *
     * @param tasks the tasks to be delegated and completed
     * @return formatted string listing tasks with descriptions and expected outputs
     * @deprecated Use {@link DefaultManagerPromptStrategy#DEFAULT}.{@link
     *     DefaultManagerPromptStrategy#buildUserPrompt(ManagerPromptContext)} instead.
     */
    @Deprecated(forRemoval = true)
    public static String buildTaskDescription(List<Task> tasks) {
        ManagerPromptContext ctx = new ManagerPromptContext(List.of(), tasks, List.of(), null);
        return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
    }
}
