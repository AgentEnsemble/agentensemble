package net.agentensemble.workflow;

import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;

/**
 * Builds the meta-prompts used by the Manager agent in a hierarchical workflow.
 *
 * The manager agent receives a structured description of available worker agents
 * (as its background, included in the system prompt) and the tasks to complete
 * (as its task description, presented in the user prompt).
 */
public final class ManagerPromptBuilder {

    private ManagerPromptBuilder() {
        // Utility class -- not instantiable
    }

    /**
     * Build the background text listing all available worker agents.
     *
     * This becomes the manager agent's background field, which is included
     * in the system prompt by AgentPromptBuilder. The background describes each
     * worker agent's role, goal, and optional background, followed by instructions
     * to use the delegateTask tool.
     *
     * @param workers the worker agents available for delegation
     * @return formatted string listing agents with roles, goals, and backgrounds
     */
    public static String buildBackground(List<Agent> workers) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following worker agents:\n\n");

        for (int i = 0; i < workers.size(); i++) {
            Agent agent = workers.get(i);
            sb.append(i + 1).append(". ").append(agent.getRole()).append("\n");
            sb.append("   Goal: ").append(agent.getGoal()).append("\n");
            if (agent.getBackground() != null && !agent.getBackground().isBlank()) {
                sb.append("   Background: ").append(agent.getBackground()).append("\n");
            }
            if (i < workers.size() - 1) {
                sb.append("\n");
            }
        }

        sb.append("\n");
        sb.append("Use the delegateTask tool to assign work to your team members. ");
        sb.append("Review their outputs and synthesize a comprehensive final result.");

        return sb.toString();
    }

    /**
     * Build the task description text listing all tasks to complete.
     *
     * This becomes the manager's task description, presented as the user prompt.
     * It lists each task with its description and expected output, and instructs
     * the manager to delegate and synthesize.
     *
     * @param tasks the tasks to be delegated and completed
     * @return formatted string listing tasks with descriptions and expected outputs
     */
    public static String buildTaskDescription(List<Task> tasks) {
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
