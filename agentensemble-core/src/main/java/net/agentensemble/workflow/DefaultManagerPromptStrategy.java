package net.agentensemble.workflow;

import net.agentensemble.Agent;
import net.agentensemble.Task;

/**
 * The default {@link ManagerPromptStrategy} implementation.
 *
 * <p>Produces the same system and user prompts that {@link ManagerPromptBuilder} generated
 * prior to the introduction of the strategy interface:
 * <ul>
 *   <li>{@link #buildSystemPrompt} -- lists all available worker agents by role, goal, and
 *       optional background, and instructs the manager to use the {@code delegateTask} tool.</li>
 *   <li>{@link #buildUserPrompt} -- lists all tasks with their descriptions and expected
 *       outputs, and instructs the manager to delegate and synthesize.</li>
 * </ul>
 *
 * <p>Use the pre-built singleton {@link #DEFAULT} rather than constructing new instances.
 * A custom strategy can extend or replace this behaviour by delegating to {@code DEFAULT}
 * before appending additional instructions:
 *
 * <pre>
 * (ctx) -> DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx)
 *     + "\n\nAdditional constraint: always prefer the Analyst agent for data tasks."
 * </pre>
 */
public final class DefaultManagerPromptStrategy implements ManagerPromptStrategy {

    /** Shared singleton; prefer this over constructing new instances. */
    public static final DefaultManagerPromptStrategy DEFAULT = new DefaultManagerPromptStrategy();

    /** Constructor is package-private to allow subclassing within the framework if needed. */
    DefaultManagerPromptStrategy() {}

    /**
     * {@inheritDoc}
     *
     * <p>Produces a numbered list of all worker agents, including each agent's role, goal,
     * and optional background, followed by an instruction to use the {@code delegateTask} tool
     * and synthesize a final result.
     */
    @Override
    public String buildSystemPrompt(ManagerPromptContext context) {
        var agents = context.agents();
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following worker agents:\n\n");

        for (int i = 0; i < agents.size(); i++) {
            Agent agent = agents.get(i);
            sb.append(i + 1).append(". ").append(agent.getRole()).append("\n");
            sb.append("   Goal: ").append(agent.getGoal()).append("\n");
            if (agent.getBackground() != null && !agent.getBackground().isBlank()) {
                sb.append("   Background: ").append(agent.getBackground()).append("\n");
            }
            if (i < agents.size() - 1) {
                sb.append("\n");
            }
        }

        sb.append("\n");
        sb.append("Use the delegateTask tool to assign work to your team members. ");
        sb.append("Review their outputs and synthesize a comprehensive final result.");

        return sb.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Produces a numbered list of all tasks, each with its description and expected output,
     * followed by an instruction to delegate each task and synthesize the results.
     */
    @Override
    public String buildUserPrompt(ManagerPromptContext context) {
        var tasks = context.tasks();
        StringBuilder sb = new StringBuilder();
        sb.append("You must coordinate your team to complete the following tasks:\n\n");

        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            sb.append("Task ")
                    .append(i + 1)
                    .append(": ")
                    .append(task.getDescription())
                    .append("\n");
            sb.append("Expected output: ").append(task.getExpectedOutput()).append("\n");
            if (i < tasks.size() - 1) {
                sb.append("\n");
            }
        }

        sb.append("\n");
        sb.append("Delegate each task to the most appropriate team member using the delegateTask tool. ");
        sb.append("Once all tasks are complete, synthesize the results into a comprehensive final response.");

        return sb.toString();
    }
}
