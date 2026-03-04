package net.agentensemble.workflow;

/**
 * Strategy interface for building the system and user prompts used by the Manager agent
 * in a hierarchical workflow.
 *
 * <p>The default implementation ({@link DefaultManagerPromptStrategy#DEFAULT}) replicates the
 * behaviour of the original {@link ManagerPromptBuilder}: it lists available worker agents
 * in the system prompt and the tasks to orchestrate in the user prompt.
 *
 * <p>Implement this interface to inject domain-specific context into the Manager agent's
 * prompts -- for example, organisational constraints, custom tool descriptions, or an
 * alternative manager persona -- without forking framework internals.
 *
 * <p>Register a custom strategy via {@code Ensemble.Builder.managerPromptStrategy(...)}:
 *
 * <pre>
 * Ensemble.builder()
 *     .workflow(Workflow.HIERARCHICAL)
 *     .managerPromptStrategy(new ManagerPromptStrategy() {
 *         {@literal @}Override
 *         public String buildSystemPrompt(ManagerPromptContext ctx) {
 *             return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx)
 *                 + "\n\nAdditional constraint: always prefer the Analyst agent for data tasks.";
 *         }
 *         {@literal @}Override
 *         public String buildUserPrompt(ManagerPromptContext ctx) {
 *             return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
 *         }
 *     })
 *     .build();
 * </pre>
 *
 * <p>This strategy is only exercised for {@code HIERARCHICAL} workflow; sequential and
 * parallel workflows are unaffected.
 */
public interface ManagerPromptStrategy {

    /**
     * Builds the system-level prompt for the Manager agent.
     *
     * <p>This prompt is provided as the manager's {@code background} field, which
     * {@code AgentPromptBuilder} includes in the system prompt. It typically describes
     * the available worker agents and how the manager should coordinate them.
     *
     * <p>Called once per task execution in a hierarchical workflow.
     *
     * @param context the context object carrying agents, tasks, prior outputs, and description
     * @return the system prompt string; must not be null
     */
    String buildSystemPrompt(ManagerPromptContext context);

    /**
     * Builds the user-level prompt for the Manager agent.
     *
     * <p>This prompt becomes the manager's task description, presented as the first
     * user message. It typically lists all tasks to orchestrate and instructs the
     * manager to delegate and synthesize results.
     *
     * <p>Called once per task execution in a hierarchical workflow.
     *
     * @param context the context object carrying agents, tasks, prior outputs, and description
     * @return the user prompt string; must not be null
     */
    String buildUserPrompt(ManagerPromptContext context);
}
