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
import net.agentensemble.reflection.TaskReflection;
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
        StringBuilder sb = new StringBuilder(256);

        // Role line
        sb.append("You are ").append(agent.getRole()).append('.');

        // Background (optional)
        String background = agent.getBackground();
        if (background != null && !background.isBlank()) {
            sb.append('\n').append(background);
        }

        // Goal
        sb.append("\n\nYour personal goal is: ").append(agent.getGoal());

        // Standard instructions
        sb.append("\n\nYou must produce a final answer that satisfies the expected output described in the task.\n"
                + "Focus on quality and accuracy. "
                + "Do not add unnecessary preamble or postscript to your final answer.");

        // Response format (optional)
        String responseFormat = agent.getResponseFormat();
        if (responseFormat != null && !responseFormat.isBlank()) {
            sb.append("\nOutput format instructions: ").append(responseFormat);
        }

        String prompt = sb.toString().stripTrailing();
        if (log.isDebugEnabled()) {
            log.debug("Built system prompt ({} chars) for agent '{}'", prompt.length(), agent.getRole());
        }
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
     * Build the user prompt for a task, injecting task-scoped memory entries, reflection
     * notes from prior runs, and legacy memory sections as applicable.
     *
     * <p>For each scope declared on the task (via {@code Task.builder().memory(...)}), up to
     * {@value #DEFAULT_SCOPE_MAX_RESULTS} entries are retrieved from the {@code memoryStore}
     * and injected as a {@code ## Memory: {scope}} section before the task description.
     * Legacy short-term, long-term, and entity memory sections follow when active.
     *
     * <p>When {@code priorReflection} is non-null (a stored reflection from a previous run),
     * a {@code ## Task Improvement Notes} section is injected before the task description.
     * This provides the agent with learned improvements to the task's instructions, creating
     * a self-optimizing prompt loop across separate {@code Ensemble.run()} invocations.
     *
     * @param task           the task to build the prompt for
     * @param contextOutputs outputs from prior tasks to include as context
     *                       (used only when short-term memory is not active)
     * @param memoryContext  runtime legacy memory state; use {@link MemoryContext#disabled()}
     *                       when memory is not configured
     * @param memoryStore    optional v2.0.0 scoped memory store; may be {@code null}
     * @param priorReflection optional reflection from the previous run; may be {@code null}
     * @return the user prompt string
     */
    public static String buildUserPrompt(
            Task task,
            List<TaskOutput> contextOutputs,
            MemoryContext memoryContext,
            MemoryStore memoryStore,
            TaskReflection priorReflection) {
        return buildUserPromptInternal(task, contextOutputs, memoryContext, memoryStore, priorReflection);
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
        return buildUserPromptInternal(task, contextOutputs, memoryContext, memoryStore, null);
    }

    private static String buildUserPromptInternal(
            Task task,
            List<TaskOutput> contextOutputs,
            MemoryContext memoryContext,
            MemoryStore memoryStore,
            TaskReflection priorReflection) {
        StringBuilder sb = new StringBuilder(1024);

        // Task-scoped memory sections (v2.0.0 MemoryStore API)
        if (memoryStore != null
                && task.getMemoryScopes() != null
                && !task.getMemoryScopes().isEmpty()) {
            for (MemoryScope scope : task.getMemoryScopes()) {
                List<MemoryEntry> scopeEntries =
                        memoryStore.retrieve(scope.getName(), task.getDescription(), DEFAULT_SCOPE_MAX_RESULTS);
                if (!scopeEntries.isEmpty()) {
                    sb.append("## Memory: ")
                            .append(scope.getName())
                            .append('\n')
                            .append("The following information from scope \"")
                            .append(scope.getName())
                            .append("\" may be relevant:\n");
                    for (MemoryEntry entry : scopeEntries) {
                        sb.append("\n---\n")
                                .append(entry.getContent())
                                .append('\n')
                                .append("---");
                    }
                    sb.append("\n\n");
                }
            }
        }

        if (memoryContext.hasShortTerm()) {
            // Short-term memory replaces explicit context (STM is a superset)
            List<MemoryEntry> stmEntries = memoryContext.getShortTermEntries();
            if (!stmEntries.isEmpty()) {
                sb.append("## Short-Term Memory (Current Run)\n"
                        + "The following outputs from earlier tasks in this run may be relevant:\n");
                for (MemoryEntry entry : stmEntries) {
                    String agentRole = entry.getMeta(MemoryEntry.META_AGENT_ROLE);
                    String taskDesc = entry.getMeta(MemoryEntry.META_TASK_DESCRIPTION);
                    sb.append("\n---\n### ")
                            .append(agentRole != null ? agentRole : "Agent")
                            .append(": ")
                            .append(taskDesc != null ? taskDesc : "")
                            .append('\n')
                            .append(entry.getContent())
                            .append('\n')
                            .append("---");
                }
                sb.append("\n\n");
            }
        } else {
            // No short-term memory: fall back to explicit context declarations
            if (contextOutputs != null && !contextOutputs.isEmpty()) {
                sb.append("## Context from Previous Tasks\n"
                        + "The following results from previous tasks may be relevant:\n");
                for (TaskOutput ctx : contextOutputs) {
                    warnIfLargeContext(ctx);
                    sb.append("\n---\n### ")
                            .append(ctx.getAgentRole())
                            .append(": ")
                            .append(ctx.getTaskDescription())
                            .append('\n');
                    String raw = ctx.getRaw();
                    sb.append(raw != null ? raw : "").append('\n').append("---\n");
                }
                sb.append('\n');
            }
        }

        // Long-term memory section
        if (memoryContext.hasLongTerm()) {
            List<MemoryEntry> ltmEntries = memoryContext.queryLongTerm(task.getDescription());
            if (!ltmEntries.isEmpty()) {
                sb.append("## Long-Term Memory\n"
                        + "The following information recalled from past experience may be relevant:\n"
                        + "\n---\n");
                for (MemoryEntry entry : ltmEntries) {
                    sb.append("- ").append(entry.getContent()).append('\n');
                }
                sb.append("---\n\n");
            }
        }

        // Entity knowledge section
        if (memoryContext.hasEntityMemory()) {
            Map<String, String> entityFacts = memoryContext.getEntityFacts();
            if (!entityFacts.isEmpty()) {
                sb.append("## Entity Knowledge\n" + "The following known facts may be relevant:\n\n");
                for (Map.Entry<String, String> entry : entityFacts.entrySet()) {
                    sb.append("- **")
                            .append(entry.getKey())
                            .append("**: ")
                            .append(entry.getValue())
                            .append('\n');
                }
                sb.append('\n');
            }
        }

        // Task improvement notes section -- injected from stored reflection of prior runs
        if (priorReflection != null) {
            sb.append("## Task Improvement Notes (from prior executions)\n"
                    + "The following refinements were identified by analyzing previous runs of this task.\n"
                    + "Apply them to improve your approach while still fulfilling the original requirements"
                    + " below.\n\n"
                    + "### Refined Instructions\n");
            sb.append(priorReflection.refinedDescription()).append("\n\n### Output Guidance\n");
            sb.append(priorReflection.refinedExpectedOutput()).append('\n');
            if (!priorReflection.observations().isEmpty()) {
                sb.append("\n### Observations\n");
                for (String obs : priorReflection.observations()) {
                    sb.append("- ").append(obs).append('\n');
                }
            }
            if (!priorReflection.suggestions().isEmpty()) {
                sb.append("\n### Suggestions\n");
                for (String sug : priorReflection.suggestions()) {
                    sb.append("- ").append(sug).append('\n');
                }
            }
            sb.append("\n---\n\n");
        }

        // Revision instructions section -- only present when this task is part of a phase retry
        if (task.getRevisionFeedback() != null && !task.getRevisionFeedback().isBlank()) {
            sb.append("## Revision Instructions");
            if (task.getAttemptNumber() > 0) {
                sb.append(" (Attempt ").append(task.getAttemptNumber() + 1).append(')');
            }
            sb.append('\n');
            sb.append("This task is being re-executed based on reviewer feedback. "
                    + "Incorporate the feedback below into your response.\n\n"
                    + "### Feedback\n");
            sb.append(task.getRevisionFeedback());
            if (task.getPriorAttemptOutput() != null
                    && !task.getPriorAttemptOutput().isBlank()) {
                sb.append("\n\n### Previous Output\n").append(task.getPriorAttemptOutput());
            }
            sb.append("\n\n");
        }

        // Task section
        sb.append("## Task\n").append(task.getDescription());

        // Expected output section
        sb.append("\n\n## Expected Output\n").append(task.getExpectedOutput());

        // Structured output format section (only when outputType is set)
        if (task.getOutputType() != null) {
            String schemaDescription = JsonSchemaGenerator.generate(task.getOutputType());
            sb.append("\n\n## Output Format\n"
                    + "You MUST respond with ONLY valid JSON and nothing else. "
                    + "Do not include markdown fences, preamble, or explanation.\n"
                    + "Your response must be ONLY valid JSON matching this schema "
                    + "(object, array, or scalar as appropriate):\n\n");
            sb.append(schemaDescription);
        }

        String prompt = sb.toString();
        if (log.isDebugEnabled()) {
            log.debug(
                    "Built user prompt ({} chars) for task '{}'", prompt.length(), truncate(task.getDescription(), 80));
        }
        return prompt;
    }

    private static void warnIfLargeContext(TaskOutput ctx) {
        if (ctx.getRaw() != null && ctx.getRaw().length() > CONTEXT_LENGTH_WARN_THRESHOLD) {
            if (log.isWarnEnabled()) {
                log.warn(
                        "Context from task '{}' is {} characters (>{}). Consider breaking into smaller tasks.",
                        truncate(ctx.getTaskDescription(), 80),
                        ctx.getRaw().length(),
                        CONTEXT_LENGTH_WARN_THRESHOLD);
            }
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
