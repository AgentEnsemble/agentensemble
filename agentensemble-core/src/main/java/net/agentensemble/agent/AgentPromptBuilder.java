package net.agentensemble.agent;

import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Constructs system and user prompts for agent-LLM interactions.
 *
 * The system prompt establishes the agent's identity (role, background, goal)
 * and standard instructions. The user prompt presents the task and any
 * relevant context from prior tasks.
 */
public final class AgentPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptBuilder.class);

    /** Log a WARN when a single context output exceeds this character count. */
    private static final int CONTEXT_LENGTH_WARN_THRESHOLD = 10_000;

    private AgentPromptBuilder() {
        // Utility class -- not instantiable
    }

    /**
     * Build the system prompt for an agent.
     *
     * <p>Format:
     * <pre>
     * You are {role}.
     * {background -- only if non-null and non-blank}
     *
     * Your personal goal is: {goal}
     *
     * You must produce a final answer...
     * Output format instructions: {responseFormat -- only if non-blank}
     * </pre>
     *
     * @param agent the agent whose prompt to build
     * @return the system prompt string
     */
    public static String buildSystemPrompt(Agent agent) {
        StringBuilder sb = new StringBuilder();

        // Role line
        sb.append("You are ").append(agent.getRole()).append(".");

        // Background (optional)
        String background = agent.getBackground();
        if (background != null && !background.isBlank()) {
            sb.append("\n").append(background);
        }

        // Goal
        sb.append("\n\n");
        sb.append("Your personal goal is: ").append(agent.getGoal());

        // Standard instructions
        sb.append("\n\n");
        sb.append("You must produce a final answer that satisfies the expected output described in the task.\n");
        sb.append("Focus on quality and accuracy. ");
        sb.append("Do not add unnecessary preamble or postscript to your final answer.");

        // Response format (optional)
        String responseFormat = agent.getResponseFormat();
        if (responseFormat != null && !responseFormat.isBlank()) {
            sb.append("\nOutput format instructions: ").append(responseFormat);
        }

        String prompt = sb.toString();
        log.debug("Built system prompt ({} chars) for agent '{}'", prompt.length(), agent.getRole());
        return prompt;
    }

    /**
     * Build the user prompt for a task, including context from prior tasks.
     *
     * <p>Format (with context):
     * <pre>
     * ## Context from Previous Tasks
     * The following results from previous tasks may be relevant:
     *
     * ---
     * ### {agentRole}: {taskDescription}
     * {raw}
     * ---
     *
     * ## Task
     * {description}
     *
     * ## Expected Output
     * {expectedOutput}
     * </pre>
     *
     * @param task the task to build the prompt for
     * @param contextOutputs outputs from prior tasks to include as context
     * @return the user prompt string
     */
    public static String buildUserPrompt(Task task, List<TaskOutput> contextOutputs) {
        StringBuilder sb = new StringBuilder();

        // Context section (only if there are context outputs)
        if (contextOutputs != null && !contextOutputs.isEmpty()) {
            sb.append("## Context from Previous Tasks\n");
            sb.append("The following results from previous tasks may be relevant:\n");

            for (TaskOutput ctx : contextOutputs) {
                warnIfLargeContext(ctx);
                sb.append("\n---\n");
                sb.append("### ").append(ctx.getAgentRole())
                        .append(": ").append(ctx.getTaskDescription()).append("\n");
                sb.append(ctx.getRaw()).append("\n");
                sb.append("---");
            }

            sb.append("\n\n");
        }

        // Task section
        sb.append("## Task\n");
        sb.append(task.getDescription());

        // Expected output section
        sb.append("\n\n## Expected Output\n");
        sb.append(task.getExpectedOutput());

        String prompt = sb.toString();
        log.debug("Built user prompt ({} chars) for task '{}'",
                prompt.length(), truncate(task.getDescription(), 80));
        return prompt;
    }

    private static void warnIfLargeContext(TaskOutput ctx) {
        if (ctx.getRaw() != null && ctx.getRaw().length() > CONTEXT_LENGTH_WARN_THRESHOLD) {
            log.warn("Context from task '{}' is {} characters (>{}). "
                    + "Consider breaking into smaller tasks.",
                    truncate(ctx.getTaskDescription(), 80),
                    ctx.getRaw().length(),
                    CONTEXT_LENGTH_WARN_THRESHOLD);
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
