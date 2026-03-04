package net.agentensemble.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.delegation.AgentDelegationTool;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailInput;
import net.agentensemble.guardrail.GuardrailOutput;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.guardrail.GuardrailViolationException.GuardrailType;
import net.agentensemble.guardrail.InputGuardrail;
import net.agentensemble.guardrail.OutputGuardrail;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a single agent on a single task.
 *
 * <p>Manages the ReAct-style tool-calling loop: the agent reasons, optionally calls
 * tools, incorporates results, and eventually produces a final text answer.
 *
 * <p><strong>Parallel tool execution:</strong> When the LLM requests multiple tools in a
 * single turn, they are executed concurrently using the {@link ExecutionContext#toolExecutor()}.
 * The default is a virtual-thread-per-task executor, so I/O-bound tools (HTTP, subprocess)
 * do not block platform threads. Single tool calls execute directly without async overhead.
 *
 * <p>When the {@link ExecutionContext} carries an active {@link net.agentensemble.memory.MemoryContext},
 * relevant memories are injected into the user prompt before execution and the task output
 * is recorded into memory after execution.
 *
 * <p>When the {@link ExecutionContext} carries {@link net.agentensemble.callback.EnsembleListener}
 * instances, a {@link ToolCallEvent} is fired after each tool execution in the ReAct loop.
 *
 * <p>Stateless -- all state is held in local variables during execution.
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /** Number of "stop" messages to send before throwing MaxIterationsExceededException. */
    private static final int MAX_STOP_MESSAGES = 3;

    /** Truncation length for tool input/output in INFO logs. */
    private static final int LOG_TRUNCATE_LENGTH = 200;

    /**
     * Execute the given task using the agent specified in the task.
     *
     * @param task             the task to execute
     * @param contextOutputs   outputs from prior tasks to include as context
     * @param executionContext execution context bundling memory, verbose flag, listeners,
     *                         tool executor, and tool metrics; if null, {@link ExecutionContext#disabled()} is used
     * @return the task output
     * @throws AgentExecutionException        if the LLM throws an error
     * @throws MaxIterationsExceededException if the agent exceeds its iteration limit
     */
    public TaskOutput execute(Task task, List<TaskOutput> contextOutputs, ExecutionContext executionContext) {
        return execute(task, contextOutputs, executionContext, null);
    }

    /**
     * Execute the given task using the agent specified in the task, with optional
     * agent delegation support.
     *
     * @param task              the task to execute
     * @param contextOutputs    outputs from prior tasks to include as context
     * @param executionContext  execution context; if null, {@link ExecutionContext#disabled()} is used
     * @param delegationContext delegation state for this run; pass {@code null} when
     *                          delegation is not enabled for this ensemble
     * @return the task output
     * @throws AgentExecutionException        if the LLM throws an error
     * @throws MaxIterationsExceededException if the agent exceeds its iteration limit
     */
    public TaskOutput execute(
            Task task,
            List<TaskOutput> contextOutputs,
            ExecutionContext executionContext,
            DelegationContext delegationContext) {

        if (executionContext == null) {
            executionContext = ExecutionContext.disabled();
        }

        boolean verbose = executionContext.isVerbose();

        Instant startTime = Instant.now();
        Agent agent = task.getAgent();
        boolean effectiveVerbose = verbose || agent.isVerbose();

        runInputGuardrails(task, contextOutputs);

        String systemPrompt = AgentPromptBuilder.buildSystemPrompt(agent);
        String userPrompt = AgentPromptBuilder.buildUserPrompt(task, contextOutputs, executionContext.memoryContext());

        if (effectiveVerbose) {
            log.info("System prompt:\n{}", systemPrompt);
            log.info("User prompt:\n{}", userPrompt);
        } else {
            log.debug("System prompt ({} chars):\n{}", systemPrompt.length(), systemPrompt);
            log.debug("User prompt ({} chars):\n{}", userPrompt.length(), userPrompt);
        }

        List<Object> effectiveTools = buildEffectiveTools(agent, delegationContext);

        log.info(
                "Agent '{}' executing task | Tools: {} | AllowDelegation: {}",
                agent.getRole(),
                effectiveTools.size(),
                agent.isAllowDelegation());

        // Resolve tools, injecting ToolContext into AbstractAgentTool instances
        ToolResolver.ResolvedTools resolvedTools =
                ToolResolver.resolve(effectiveTools, executionContext.toolMetrics(), executionContext.toolExecutor());
        AtomicInteger toolCallCounter = new AtomicInteger(0);

        String finalResponse;

        try {
            if (resolvedTools.hasTools()) {
                finalResponse = executeWithTools(
                        agent, task, systemPrompt, userPrompt, resolvedTools, toolCallCounter, executionContext);
            } else {
                finalResponse = executeWithoutTools(agent, systemPrompt, userPrompt);
                log.debug("Agent '{}' completed (no tools)", agent.getRole());
            }
        } catch (AgentExecutionException | MaxIterationsExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Agent '" + agent.getRole() + "' failed: " + e.getMessage(),
                    agent.getRole(),
                    task.getDescription(),
                    e);
        }

        if (finalResponse == null || finalResponse.isBlank()) {
            log.warn(
                    "Agent '{}' returned empty response for task '{}'",
                    agent.getRole(),
                    truncate(task.getDescription(), 80));
            finalResponse = finalResponse != null ? finalResponse : "";
        }

        if (effectiveVerbose) {
            log.info("Agent response:\n{}", finalResponse);
        } else {
            log.trace("Full agent response:\n{}", finalResponse);
        }

        Object parsedOutput = null;
        if (task.getOutputType() != null) {
            parsedOutput = StructuredOutputHandler.parse(agent, task, finalResponse, systemPrompt);
        }

        runOutputGuardrails(task, finalResponse, parsedOutput);

        Duration duration = Duration.between(startTime, Instant.now());
        int toolCalls = toolCallCounter.get();
        log.debug("Agent '{}' completed | Tool calls: {} | Duration: {}", agent.getRole(), toolCalls, duration);

        TaskOutput output = TaskOutput.builder()
                .raw(finalResponse)
                .taskDescription(task.getDescription())
                .agentRole(agent.getRole())
                .completedAt(Instant.now())
                .duration(duration)
                .toolCallCount(toolCalls)
                .parsedOutput(parsedOutput)
                .outputType(task.getOutputType())
                .build();

        executionContext.memoryContext().record(output);

        return output;
    }

    /**
     * Build the effective tool list for this execution.
     * When the agent has {@code allowDelegation = true} and a non-null
     * {@code delegationContext} is provided, an {@link AgentDelegationTool} is prepended.
     */
    private List<Object> buildEffectiveTools(Agent agent, DelegationContext delegationContext) {
        if (agent.isAllowDelegation() && delegationContext != null) {
            AgentDelegationTool delegationTool = new AgentDelegationTool(agent.getRole(), delegationContext);
            List<Object> tools = new ArrayList<>();
            tools.add(delegationTool);
            tools.addAll(agent.getTools());
            log.debug(
                    "Agent '{}' delegation tool injected (depth {}/{})",
                    agent.getRole(),
                    delegationContext.getCurrentDepth(),
                    delegationContext.getMaxDepth());
            return tools;
        }
        return agent.getTools();
    }

    // ========================
    // Execution paths
    // ========================

    private String executeWithoutTools(Agent agent, String systemPrompt, String userPrompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)))
                .build();
        ChatResponse response = agent.getLlm().chat(request);
        return response.aiMessage().text();
    }

    private String executeWithTools(
            Agent agent,
            Task task,
            String systemPrompt,
            String userPrompt,
            ToolResolver.ResolvedTools resolvedTools,
            AtomicInteger toolCallCounter,
            ExecutionContext executionContext) {

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        int stopMessageCount = 0;
        int maxIterations = agent.getMaxIterations();
        Executor toolExecutor = executionContext.toolExecutor();
        String agentRole = agent.getRole();

        while (true) {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(resolvedTools.allSpecifications())
                    .build();

            ChatResponse response = agent.getLlm().chat(request);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                // LLM produced a text response -- done
                return aiMessage.text();
            }

            List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();

            if (toolRequests.size() == 1) {
                // Single tool: execute directly without async overhead
                ToolExecutionRequest toolRequest = toolRequests.get(0);
                ToolExecutionResultMessage resultMsg = executeSingleTool(
                        toolRequest,
                        resolvedTools,
                        toolCallCounter,
                        maxIterations,
                        agentRole,
                        executionContext,
                        messages);
                if (resultMsg == null) {
                    // Over limit: stop message was added by executeSingleTool
                    stopMessageCount++;
                    if (stopMessageCount >= MAX_STOP_MESSAGES) {
                        throw new MaxIterationsExceededException(
                                agentRole, task.getDescription(), maxIterations, toolCallCounter.get());
                    }
                }
            } else {
                // Multiple tools: execute in parallel using the tool executor
                stopMessageCount = executeParallelTools(
                        toolRequests,
                        resolvedTools,
                        toolCallCounter,
                        maxIterations,
                        agentRole,
                        executionContext,
                        toolExecutor,
                        task,
                        messages,
                        stopMessageCount);
            }
        }
    }

    /**
     * Execute a single tool call. Returns null if the iteration limit was hit
     * (a stop message has been added to {@code messages}).
     */
    private ToolExecutionResultMessage executeSingleTool(
            ToolExecutionRequest toolRequest,
            ToolResolver.ResolvedTools resolvedTools,
            AtomicInteger toolCallCounter,
            int maxIterations,
            String agentRole,
            ExecutionContext executionContext,
            List<ChatMessage> messages) {

        if (toolCallCounter.get() >= maxIterations) {
            String stopText = buildStopText(maxIterations);
            messages.add(new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), stopText));
            log.warn(
                    "Agent '{}' exceeded max iterations ({}) on tool '{}'.",
                    agentRole,
                    maxIterations,
                    toolRequest.name());
            return null;
        }

        toolCallCounter.incrementAndGet();
        Instant toolStart = Instant.now();
        ToolResult toolResult = resolvedTools.execute(toolRequest, agentRole);
        Duration toolDuration = Duration.between(toolStart, Instant.now());
        String toolResultText = toText(toolResult);

        logToolCall(agentRole, toolRequest, toolResultText, toolDuration);
        executionContext.fireToolCall(new ToolCallEvent(
                toolRequest.name(),
                toolRequest.arguments(),
                toolResultText,
                toolResult.getStructuredOutput(),
                agentRole,
                toolDuration));

        ToolExecutionResultMessage resultMsg =
                new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), toolResultText);
        messages.add(resultMsg);
        return resultMsg;
    }

    /**
     * Execute multiple tool calls in parallel using the tool executor.
     * Results are added to {@code messages} in the same order as the requests.
     * Returns the updated {@code stopMessageCount}.
     */
    private int executeParallelTools(
            List<ToolExecutionRequest> toolRequests,
            ToolResolver.ResolvedTools resolvedTools,
            AtomicInteger toolCallCounter,
            int maxIterations,
            String agentRole,
            ExecutionContext executionContext,
            Executor toolExecutor,
            Task task,
            List<ChatMessage> messages,
            int stopMessageCount) {

        log.debug("Agent '{}' executing {} tools in parallel", agentRole, toolRequests.size());

        // Pre-check per request whether we can execute it
        record PendingTool(ToolExecutionRequest request, boolean withinLimit) {}

        List<PendingTool> pending = new ArrayList<>();
        for (ToolExecutionRequest req : toolRequests) {
            // Atomically check-and-increment: only increment if within limit
            int current = toolCallCounter.get();
            if (current < maxIterations) {
                toolCallCounter.incrementAndGet();
                pending.add(new PendingTool(req, true));
            } else {
                pending.add(new PendingTool(req, false));
            }
        }

        // Launch all executable tools in parallel
        record ToolExecution(ToolExecutionRequest request, ToolResult result, Duration duration, boolean withinLimit) {}

        List<CompletableFuture<ToolExecution>> futures = new ArrayList<>();
        for (PendingTool pt : pending) {
            if (pt.withinLimit()) {
                CompletableFuture<ToolExecution> future = CompletableFuture.supplyAsync(
                        () -> {
                            Instant start = Instant.now();
                            ToolResult result = resolvedTools.execute(pt.request(), agentRole);
                            Duration dur = Duration.between(start, Instant.now());
                            return new ToolExecution(pt.request(), result, dur, true);
                        },
                        toolExecutor);
                futures.add(future);
            } else {
                // Over limit: resolve immediately as a stop message
                CompletableFuture<ToolExecution> immediate =
                        CompletableFuture.completedFuture(new ToolExecution(pt.request(), null, Duration.ZERO, false));
                futures.add(immediate);
            }
        }

        // Wait for all futures then process in order
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int updatedStopCount = stopMessageCount;
        for (CompletableFuture<ToolExecution> future : futures) {
            ToolExecution te = future.join();
            if (!te.withinLimit()) {
                String stopText = buildStopText(maxIterations);
                messages.add(new ToolExecutionResultMessage(
                        te.request().id(), te.request().name(), stopText));
                log.warn(
                        "Agent '{}' exceeded max iterations ({}) on tool '{}'.",
                        agentRole,
                        maxIterations,
                        te.request().name());
                updatedStopCount++;
                if (updatedStopCount >= MAX_STOP_MESSAGES) {
                    throw new MaxIterationsExceededException(
                            agentRole, task.getDescription(), maxIterations, toolCallCounter.get());
                }
            } else {
                String toolResultText = toText(te.result());
                logToolCall(agentRole, te.request(), toolResultText, te.duration());
                executionContext.fireToolCall(new ToolCallEvent(
                        te.request().name(),
                        te.request().arguments(),
                        toolResultText,
                        te.result().getStructuredOutput(),
                        agentRole,
                        te.duration()));
                messages.add(new ToolExecutionResultMessage(
                        te.request().id(), te.request().name(), toolResultText));
            }
        }

        return updatedStopCount;
    }

    // ========================
    // Guardrail invocation
    // ========================

    private void runInputGuardrails(Task task, List<TaskOutput> contextOutputs) {
        List<InputGuardrail> guardrails = task.getInputGuardrails();
        if (guardrails.isEmpty()) {
            return;
        }
        Agent agent = task.getAgent();
        GuardrailInput input =
                new GuardrailInput(task.getDescription(), task.getExpectedOutput(), contextOutputs, agent.getRole());

        for (InputGuardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.validate(input);
            if (!result.isSuccess()) {
                log.warn(
                        "Input guardrail blocked agent '{}' task '{}': {}",
                        agent.getRole(),
                        truncate(task.getDescription(), 80),
                        result.getMessage());
                throw new GuardrailViolationException(
                        GuardrailType.INPUT, result.getMessage(), task.getDescription(), agent.getRole());
            }
        }
    }

    private void runOutputGuardrails(Task task, String rawResponse, Object parsedOutput) {
        List<OutputGuardrail> guardrails = task.getOutputGuardrails();
        if (guardrails.isEmpty()) {
            return;
        }
        Agent agent = task.getAgent();
        GuardrailOutput output = new GuardrailOutput(rawResponse, parsedOutput, task.getDescription(), agent.getRole());

        for (OutputGuardrail guardrail : guardrails) {
            GuardrailResult result = guardrail.validate(output);
            if (!result.isSuccess()) {
                log.warn(
                        "Output guardrail blocked agent '{}' task '{}': {}",
                        agent.getRole(),
                        truncate(task.getDescription(), 80),
                        result.getMessage());
                throw new GuardrailViolationException(
                        GuardrailType.OUTPUT, result.getMessage(), task.getDescription(), agent.getRole());
            }
        }
    }

    // ========================
    // Private utilities
    // ========================

    private static String toText(ToolResult result) {
        if (result == null) return "";
        if (result.isSuccess()) {
            return result.getOutput();
        } else {
            return "Error: " + result.getErrorMessage();
        }
    }

    private static String buildStopText(int maxIterations) {
        return "STOP: Maximum tool iterations (" + maxIterations
                + ") reached. You must provide your best final answer now "
                + "based on information gathered so far.";
    }

    private static void logToolCall(
            String agentRole, ToolExecutionRequest toolRequest, String toolResultText, Duration toolDuration) {
        if (toolResultText != null && toolResultText.startsWith("Error:")) {
            log.warn(
                    "[{}] Tool error: {}({}) -> {} [{}ms]",
                    agentRole,
                    toolRequest.name(),
                    truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                    truncate(toolResultText, LOG_TRUNCATE_LENGTH),
                    toolDuration.toMillis());
        } else {
            log.info(
                    "[{}] Tool call: {}({}) -> {} [{}ms]",
                    agentRole,
                    toolRequest.name(),
                    truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                    truncate(toolResultText, LOG_TRUNCATE_LENGTH),
                    toolDuration.toMillis());
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
