package net.agentensemble.agent;

import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Constructs system and user prompts for agent-LLM interactions.
 *
 * The system prompt establishes the agent's identity (role, background, goal)
 * and standard instructions. The user prompt presents the task and any
 * relevant context from prior tasks, and optionally memory sections when
 * memory is enabled on the ensemble.
 *
 * When short-term memory is active, all prior task outputs from the current run
 * are injected as a "Short-Term Memory" section, and the explicit
 * "Context from Previous Tasks" section is omitted (STM is a superset).
 * When long-term memory is active, semantically relevant past memories are
 * retrieved and injected as a "Long-Term Memory" section.
 * When entity memory is active, all known entity facts are injected as an
 * "Entity Knowledge" section.
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
     * Build the user prompt for a task with no memory.
     *
     * Delegates to {@link #buildUserPrompt(Task, List, MemoryContext)} with
     * a disabled memory context.
     *
     * @param task           the task to build the prompt for
     * @param contextOutputs outputs from prior tasks to include as context
     * @return the user prompt string
     */
    public static String buildUserPrompt(Task task, List<TaskOutput> contextOutputs) {
        return buildUserPrompt(task, contextOutputs, MemoryContext.disabled());
    }

    /**
     * Build the user prompt for a task, including context and memory sections.
     *
     * <p>When short-term memory is active, all prior run outputs replace the
     * explicit context section (since STM is a superset of explicit context).
     * Long-term and entity memory sections are appended when active.
     *
     * <p>Format (with all memory types enabled):
     * <pre>
     * ## Short-Term Memory (Current Run)
     * The following outputs from earlier tasks in this run may be relevant:
     *
     * ---
     * ### {agentRole}: {taskDescription}
     * {content}
     * ---
     *
     * ## Long-Term Memory
     * The following information recalled from past experience may be relevant:
     *
     * ---
     * - {content}
     * ---
     *
     * ## Entity Knowledge
     * The following known facts may be relevant:
     *
     * - **{entityName}**: {fact}
     *
     * ## Task
     * {description}
     *
     * ## Expected Output
     * {expectedOutput}
     * </pre>
     *
     * @param task           the task to build the prompt for
     * @param contextOutputs outputs from prior tasks to include as context
     *                       (used only when short-term memory is not active)
     * @param memoryContext  runtime memory state; use {@link MemoryContext#disabled()}
     *                       when memory is not configured
     * @return the user prompt string
     */
    public static String buildUserPrompt(Task task, List<TaskOutput> contextOutputs,
            MemoryContext memoryContext) {
        StringBuilder sb = new StringBuilder();

        if (memoryContext.hasShortTerm()) {
            // Short-term memory replaces explicit context (STM is a superset)
            List<MemoryEntry> stmEntries = memoryContext.getShortTermEntries();
            if (!stmEntries.isEmpty()) {
                sb.append("## Short-Term Memory (Current Run)\n");
                sb.append("The following outputs from earlier tasks in this run may be relevant:\n");
                for (MemoryEntry entry : stmEntries) {
                    sb.append("\n---\n");
                    sb.append("### ").append(entry.getAgentRole())
                            .append(": ").append(entry.getTaskDescription()).append("\n");
                    sb.append(entry.getContent()).append("\n");
                    sb.append("---");
                }
                sb.append("\n\n");
            }
        } else {
            // No short-term memory: fall back to explicit context declarations
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
        }

        // Long-term memory section
        if (memoryContext.hasLongTerm()) {
            List<MemoryEntry> ltmEntries = memoryContext.queryLongTerm(task.getDescription());
            if (!ltmEntries.isEmpty()) {
                sb.append("## Long-Term Memory\n");
                sb.append("The following information recalled from past experience may be relevant:\n");
                sb.append("\n---\n");
                for (MemoryEntry entry : ltmEntries) {
                    sb.append("- ").append(entry.getContent()).append("\n");
                }
                sb.append("---\n\n");
            }
        }

        // Entity knowledge section
        if (memoryContext.hasEntityMemory()) {
            Map<String, String> entityFacts = memoryContext.getEntityFacts();
            if (!entityFacts.isEmpty()) {
                sb.append("## Entity Knowledge\n");
                sb.append("The following known facts may be relevant:\n\n");
                for (Map.Entry<String, String> entry : entityFacts.entrySet()) {
                    sb.append("- **").append(entry.getKey()).append("**: ")
                            .append(entry.getValue()).append("\n");
                }
                sb.append("\n");
            }
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
