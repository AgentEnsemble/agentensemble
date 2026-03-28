package net.agentensemble.ensemble;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.dashboard.RequestHandler;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches incoming cross-ensemble work requests to the appropriate shared task or tool.
 *
 * <p>For task requests, the handler runs the task through the ensemble's execution pipeline
 * (using the ensemble's LLM and settings). For tool requests, the handler invokes the tool
 * directly.
 *
 * <p>Created by {@link Ensemble#start(int)} and wired into the dashboard via
 * {@link net.agentensemble.dashboard.EnsembleDashboard#setRequestHandler(RequestHandler)}.
 */
public class EnsembleRequestHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(EnsembleRequestHandler.class);

    private final Ensemble ensemble;
    private final Map<String, Task> taskRegistry;
    private final Map<String, AgentTool> toolRegistry;

    /**
     * Create a request handler for the given ensemble.
     *
     * @param ensemble the ensemble whose shared tasks/tools to dispatch to; must not be null
     */
    public EnsembleRequestHandler(Ensemble ensemble) {
        this.ensemble = Objects.requireNonNull(ensemble, "ensemble must not be null");
        this.taskRegistry = ensemble.getSharedTaskRegistry();
        this.toolRegistry = ensemble.getSharedToolRegistry();
    }

    @Override
    public TaskResult handleTaskRequest(String taskName, String context) {
        Instant start = Instant.now();
        Task task = taskRegistry.get(taskName);
        if (task == null) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            return new TaskResult("FAILED", null, "Unknown shared task: " + taskName, durationMs);
        }

        // Check if ensemble is draining
        EnsembleLifecycleState state = ensemble.getLifecycleState();
        if (state == EnsembleLifecycleState.DRAINING || state == EnsembleLifecycleState.STOPPED) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            return new TaskResult("REJECTED", null, "Ensemble is " + state.name(), durationMs);
        }

        try {
            // Execute the original shared task through the ensemble's execution pipeline,
            // preserving all task configuration (tools, guardrails, reviews, etc.)
            EnsembleOutput output = Ensemble.builder()
                    .chatLanguageModel(ensemble.getChatLanguageModel())
                    .agentSynthesizer(ensemble.getAgentSynthesizer())
                    .task(task)
                    .input("context", context != null ? context : "")
                    .build()
                    .run();

            long durationMs = Duration.between(start, Instant.now()).toMillis();
            String result = output.lastCompletedOutput().map(to -> to.getRaw()).orElse("");
            return new TaskResult("COMPLETED", result, null, durationMs);
        } catch (Exception e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.warn("Task '{}' execution failed: {}", taskName, e.getMessage(), e);
            return new TaskResult("FAILED", null, e.getMessage(), durationMs);
        }
    }

    @Override
    public ToolResult handleToolRequest(String toolName, String input) {
        Instant start = Instant.now();
        AgentTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            return new ToolResult("FAILED", null, "Unknown shared tool: " + toolName, durationMs);
        }

        // Check if ensemble is draining
        EnsembleLifecycleState state = ensemble.getLifecycleState();
        if (state == EnsembleLifecycleState.DRAINING || state == EnsembleLifecycleState.STOPPED) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            return new ToolResult("REJECTED", null, "Ensemble is " + state.name(), durationMs);
        }

        try {
            net.agentensemble.tool.ToolResult toolResult = tool.execute(input);
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            if (toolResult.isSuccess()) {
                return new ToolResult("COMPLETED", toolResult.getOutput(), null, durationMs);
            } else {
                return new ToolResult("FAILED", null, toolResult.getErrorMessage(), durationMs);
            }
        } catch (Exception e) {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            log.warn("Tool '{}' execution failed: {}", toolName, e.getMessage(), e);
            return new ToolResult("FAILED", null, e.getMessage(), durationMs);
        }
    }
}
