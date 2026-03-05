package net.agentensemble.agent;

import java.util.List;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryScope;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.output.JsonSchemaGenerator;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /** Maximum number of entries retrieved from each task-declared memory scope per prompt. */
    private static final int DEFAULT_SCOPE_MAX_RESULTS = 5;

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

        String prompt = sb.toString().stripTrailing();
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
        return buildUserPrompt(task, contextOutputs, MemoryContext.disabled(), null);
    }

    /**
     * Build the user prompt for a task, including context and legacy memory sections.
     *
     * <p>Delegates to {@link #buildUserPrompt(Task, List, MemoryContext, MemoryStore)} with a
     * {@code null} memory store (no scope-based injection).
     *
     * @param task           the task to build the prompt for
     * @param contextOutputs outputs from prior tasks to include as context
     * @param memoryContext  runtime legacy memory state
     * @return the user prompt string
     */
    public static String buildUserPrompt(Task task, List<TaskOutput> contextOutputs, MemoryContext memoryContext) {
        return buildUserPrompt(task, contextOutputs, memoryContext, null);
    }

    /**
     * Build the user prompt for a task, injecting task-scoped memory entries and legacy
     * memory sections as applicable.
     *
     * <p>For each scope declared on the task (via {@code Task.builder().memory(...)}), up to
     * {@value #DEFAULT_SCOPE_MAX_RESULTS} entries are retrieved from the {@code memoryStore}
     * and injected as a {@code ## Memory: {scope}} section before the task description.
     * Legacy short-term, long-term, and entity memory sections follow when active.
     *
     * @param task           the task to build the prompt for
     * @param contextOutputs outputs from prior tasks to include as context
     *                       (used only when short-term memory is not active)
     * @param memoryContext  runtime legacy memory state; use {@link MemoryContext#disabled()}
     *                       when memory is not configured
     * @param memoryStore    optional v2.0.0 scoped memory store; may be {@code null}
     * @return the user prompt string
     */
    public static String buildUserPrompt(
            Task task, List<TaskOutput> contextOutputs, MemoryContext memoryContext, MemoryStore memoryStore) {
        StringBuilder sb = new StringBuilder();

        // Task-scoped memory sections (v2.0.0 MemoryStore API)
        if (memoryStore != null
                && task.getMemoryScopes() != null
                && !task.getMemoryScopes().isEmpty()) {
            for (MemoryScope scope : task.getMemoryScopes()) {
                List<MemoryEntry> scopeEntries =
                        memoryStore.retrieve(scope.getName(), task.getDescription(), DEFAULT_SCOPE_MAX_RESULTS);
                if (!scopeEntries.isEmpty()) {
                    sb.append("## Memory: ").append(scope.getName()).append("\n");
                    sb.append("The following information from scope \"")
                            .append(scope.getName())
                            .append("\" may be relevant:\n");
                    for (MemoryEntry entry : scopeEntries) {
                        sb.append("\n---\n");
                        sb.append(entry.getContent()).append("\n");
                        sb.append("---");
                    }
                    sb.append("\n\n");
                }
            }
        }

        if (memoryContext.hasShortTerm()) {
            // Short-term memory replaces explicit context (STM is a superset)
            List<MemoryEntry> stmEntries = memoryContext.getShortTermEntries();
            if (!stmEntries.isEmpty()) {
                sb.append("## Short-Term Memory (Current Run)\n");
                sb.append("The following outputs from earlier tasks in this run may be relevant:\n");
                for (MemoryEntry entry : stmEntries) {
                    sb.append("\n---\n");
                    String agentRole = entry.getMeta(MemoryEntry.META_AGENT_ROLE);
                    String taskDesc = entry.getMeta(MemoryEntry.META_TASK_DESCRIPTION);
                    sb.append("### ")
                            .append(agentRole != null ? agentRole : "Agent")
                            .append(": ")
                            .append(taskDesc != null ? taskDesc : "")
                            .append("\n");
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
                    sb.append("### ")
                            .append(ctx.getAgentRole())
                            .append(": ")
                            .append(ctx.getTaskDescription())
                            .append("\n");
                    String raw = ctx.getRaw();
                    sb.append(raw != null ? raw : "").append("\n");
                    sb.append("---\n");
                }
                sb.append("\n");
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
                    sb.append("- **")
                            .append(entry.getKey())
                            .append("**: ")
                            .append(entry.getValue())
                            .append("\n");
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

        // Structured output format section (only when outputType is set)
        if (task.getOutputType() != null) {
            String schemaDescription = JsonSchemaGenerator.generate(task.getOutputType());
            sb.append("\n\n## Output Format\n");
            sb.append("You MUST respond with ONLY valid JSON and nothing else. ");
            sb.append("Do not include markdown fences, preamble, or explanation.\n");
            sb.append("Your response must be ONLY valid JSON matching this schema ");
            sb.append("(object, array, or scalar as appropriate):\n\n");
            sb.append(schemaDescription);
        }

        String prompt = sb.toString();
        log.debug("Built user prompt ({} chars) for task '{}'", prompt.length(), truncate(task.getDescription(), 80));
        return prompt;
    }

    private static void warnIfLargeContext(TaskOutput ctx) {
        if (ctx.getRaw() != null && ctx.getRaw().length() > CONTEXT_LENGTH_WARN_THRESHOLD) {
            log.warn(
                    "Context from task '{}' is {} characters (>{}). " + "Consider breaking into smaller tasks.",
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
