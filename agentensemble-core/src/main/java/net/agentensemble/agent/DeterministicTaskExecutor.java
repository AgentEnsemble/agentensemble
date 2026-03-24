package net.agentensemble.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.agentensemble.Task;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailInput;
import net.agentensemble.guardrail.GuardrailOutput;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.guardrail.GuardrailViolationException.GuardrailType;
import net.agentensemble.guardrail.InputGuardrail;
import net.agentensemble.guardrail.OutputGuardrail;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryRecord;
import net.agentensemble.memory.MemoryScope;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.task.TaskHandler;
import net.agentensemble.task.TaskHandlerContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a single deterministic task by invoking its {@link TaskHandler} directly,
 * bypassing the LLM and the ReAct tool-calling loop.
 *
 * <p>Called by the workflow executors in place of {@link AgentExecutor} when a task has
 * a {@link TaskHandler} configured ({@code task.getHandler() != null}).
 *
 * <p>The execution lifecycle mirrors that of {@link AgentExecutor}:
 * <ol>
 *   <li>Input guardrails are evaluated (if any).</li>
 *   <li>A {@link TaskHandlerContext} is built from the task and context outputs.</li>
 *   <li>The handler's {@link TaskHandler#execute(TaskHandlerContext)} is invoked.</li>
 *   <li>Output guardrails are evaluated (if any).</li>
 *   <li>The task output is stored in declared memory scopes (if any).</li>
 *   <li>A {@link TaskOutput} is returned with {@code agentRole = "(deterministic)"}
 *       and {@code toolCallCount = 0}.</li>
 * </ol>
 *
 * <p>When the handler throws an exception or returns a {@link ToolResult#failure(String)},
 * an {@link AgentExecutionException} is thrown, which the calling workflow executor
 * wraps in a {@link net.agentensemble.exception.TaskExecutionException}.
 *
 * <p>Stateless -- all state is held in local variables during execution.
 */
public class DeterministicTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(DeterministicTaskExecutor.class);

    /**
     * The agent role reported in {@link TaskOutput} and log messages for deterministic tasks.
     */
    public static final String DETERMINISTIC_ROLE = "(deterministic)";

    /** Truncation length for task description in log messages. */
    private static final int LOG_TRUNCATE_LENGTH = 80;

    /**
     * Execute a deterministic task by invoking its handler directly.
     *
     * @param task             the task to execute; must have a non-null handler
     * @param contextOutputs   outputs from prior tasks declared in {@code task.context()}
     * @param executionContext execution context for memory, metrics, and callbacks;
     *                         if null, {@link ExecutionContext#disabled()} is used
     * @return the completed task output
     * @throws AgentExecutionException    if the handler throws or returns a failure result
     * @throws GuardrailViolationException if an input or output guardrail rejects the task
     * @throws IllegalArgumentException   if the task has no handler configured
     */
    public TaskOutput execute(Task task, List<TaskOutput> contextOutputs, ExecutionContext executionContext) {
        if (task.getHandler() == null) {
            throw new IllegalArgumentException("DeterministicTaskExecutor requires a task with a handler configured; "
                    + "task '" + task.getDescription() + "' has no handler configured.");
        }

        if (executionContext == null) {
            executionContext = ExecutionContext.disabled();
        }

        if (log.isInfoEnabled()) {
            log.info(
                    "Deterministic task starting | Description: {}",
                    truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));
        }

        // Capture startTime before guardrails so duration includes guardrail evaluation time,
        // matching AgentExecutor semantics.
        Instant startTime = Instant.now();

        runInputGuardrails(task, contextOutputs);

        TaskHandlerContext context =
                new TaskHandlerContext(task.getDescription(), task.getExpectedOutput(), contextOutputs);

        ToolResult result;
        try {
            result = task.getHandler().execute(context);
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Deterministic task handler threw an exception: " + e.getMessage(),
                    DETERMINISTIC_ROLE,
                    task.getDescription(),
                    e);
        }

        if (result == null) {
            throw new AgentExecutionException(
                    "Deterministic task handler returned null for task: " + task.getDescription(),
                    DETERMINISTIC_ROLE,
                    task.getDescription(),
                    null);
        }

        if (!result.isSuccess()) {
            String errorMessage = result.getErrorMessage() != null ? result.getErrorMessage() : "handler failure";
            throw new AgentExecutionException(
                    "Deterministic task failed: " + errorMessage, DETERMINISTIC_ROLE, task.getDescription(), null);
        }

        String raw = result.getOutput() != null ? result.getOutput() : "";

        // When outputType is declared, use the structured output from the ToolResult if provided.
        // The handler is responsible for supplying the correctly-typed value via
        // ToolResult.success(text, typedValue). If structuredOutput is null, parsedOutput is null.
        Object parsedOutput = (task.getOutputType() != null) ? result.getStructuredOutput() : null;

        runOutputGuardrails(task, raw, parsedOutput);

        Instant completedAt = Instant.now();
        Duration duration = Duration.between(startTime, completedAt);

        if (log.isInfoEnabled()) {
            log.info(
                    "Deterministic task completed | Duration: {} | Description: {}",
                    duration,
                    truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));
        }

        TaskOutput output = TaskOutput.builder()
                .raw(raw)
                .taskDescription(task.getDescription())
                .agentRole(DETERMINISTIC_ROLE)
                .completedAt(completedAt)
                .duration(duration)
                .toolCallCount(0)
                .parsedOutput(parsedOutput)
                .outputType(task.getOutputType())
                .metrics(TaskMetrics.EMPTY)
                .build();

        // Store output in task-declared memory scopes
        MemoryStore memStore = executionContext.memoryStore();
        if (memStore != null
                && task.getMemoryScopes() != null
                && !task.getMemoryScopes().isEmpty()) {
            storeInDeclaredScopes(task, output, memStore);
        }

        // Legacy record call: no-op when MemoryContext is disabled (default in v2.0.0).
        // Ensures deterministic task outputs are visible to legacy STM/LTM memory so that
        // subsequent AI tasks see them in the agent prompt (matching AgentExecutor behaviour).
        executionContext
                .memoryContext()
                .record(new MemoryRecord(
                        output.getRaw(), output.getAgentRole(), output.getTaskDescription(), output.getCompletedAt()));

        return output;
    }

    // ========================
    // Guardrail helpers
    // ========================

    private static void runInputGuardrails(Task task, List<TaskOutput> contextOutputs) {
        List<InputGuardrail> guardrails = task.getInputGuardrails();
        if (guardrails.isEmpty()) {
            return;
        }
        GuardrailInput input =
                new GuardrailInput(task.getDescription(), task.getExpectedOutput(), contextOutputs, DETERMINISTIC_ROLE);

        for (InputGuardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.validate(input);
            if (!result.isSuccess()) {
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Input guardrail blocked deterministic task '{}': {}",
                            truncate(task.getDescription(), LOG_TRUNCATE_LENGTH),
                            result.getMessage());
                }
                throw new GuardrailViolationException(
                        GuardrailType.INPUT, result.getMessage(), task.getDescription(), DETERMINISTIC_ROLE);
            }
        }
    }

    private static void runOutputGuardrails(Task task, String rawOutput, Object parsedOutput) {
        List<OutputGuardrail> guardrails = task.getOutputGuardrails();
        if (guardrails.isEmpty()) {
            return;
        }
        GuardrailOutput output =
                new GuardrailOutput(rawOutput, parsedOutput, task.getDescription(), DETERMINISTIC_ROLE);

        for (OutputGuardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.validate(output);
            if (!result.isSuccess()) {
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Output guardrail blocked deterministic task '{}': {}",
                            truncate(task.getDescription(), LOG_TRUNCATE_LENGTH),
                            result.getMessage());
                }
                throw new GuardrailViolationException(
                        GuardrailType.OUTPUT, result.getMessage(), task.getDescription(), DETERMINISTIC_ROLE);
            }
        }
    }

    // ========================
    // Memory helpers
    // ========================

    private static void storeInDeclaredScopes(Task task, TaskOutput output, MemoryStore memStore) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(MemoryEntry.META_AGENT_ROLE, DETERMINISTIC_ROLE);
        if (output.getTaskDescription() != null) {
            metadata.put(MemoryEntry.META_TASK_DESCRIPTION, output.getTaskDescription());
        }
        MemoryEntry entry = MemoryEntry.builder()
                .content(output.getRaw())
                .structuredContent(output.getParsedOutput())
                .storedAt(output.getCompletedAt())
                .metadata(Map.copyOf(metadata))
                .build();

        for (MemoryScope scope : task.getMemoryScopes()) {
            memStore.store(scope.getName(), entry);
            if (scope.getEvictionPolicy() != null) {
                memStore.evict(scope.getName(), scope.getEvictionPolicy());
            }
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
