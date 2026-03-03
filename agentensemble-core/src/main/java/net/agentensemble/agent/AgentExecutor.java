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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a single agent on a single task.
 *
 * Manages the ReAct-style tool-calling loop: the agent reasons, optionally calls
 * tools, incorporates results, and eventually produces a final text answer.
 *
 * When the {@link ExecutionContext} carries an active {@link net.agentensemble.memory.MemoryContext},
 * relevant memories are injected into the user prompt before execution and the task output
 * is recorded into memory after execution.
 *
 * When the {@link ExecutionContext} carries {@link net.agentensemble.callback.EnsembleListener}
 * instances, a {@link ToolCallEvent} is fired after each tool execution in the ReAct loop.
 *
 * Stateless -- all state is held in local variables during execution.
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
     * @param executionContext execution context bundling memory, verbose flag, and listeners;
     *                         if null, {@link ExecutionContext#disabled()} is used
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
     * When {@code delegationContext} is non-null and the agent has
     * {@code allowDelegation = true}, an {@link AgentDelegationTool} is auto-injected
     * into the agent's effective tool list for this execution.
     *
     * @param task              the task to execute
     * @param contextOutputs    outputs from prior tasks to include as context
     * @param executionContext  execution context bundling memory, verbose flag, and listeners;
     *                          if null, {@link ExecutionContext#disabled()} is used
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

        // Normalize null executionContext defensively
        if (executionContext == null) {
            executionContext = ExecutionContext.disabled();
        }

        boolean verbose = executionContext.isVerbose();

        Instant startTime = Instant.now();
        Agent agent = task.getAgent();
        boolean effectiveVerbose = verbose || agent.isVerbose();

        // Run input guardrails before any LLM call -- throws GuardrailViolationException on failure
        runInputGuardrails(task, contextOutputs);

        // Build prompts -- memory context injects STM, LTM, and entity knowledge as applicable
        String systemPrompt = AgentPromptBuilder.buildSystemPrompt(agent);
        String userPrompt = AgentPromptBuilder.buildUserPrompt(task, contextOutputs, executionContext.memoryContext());

        if (effectiveVerbose) {
            log.info("System prompt:\n{}", systemPrompt);
            log.info("User prompt:\n{}", userPrompt);
        } else {
            log.debug("System prompt ({} chars):\n{}", systemPrompt.length(), systemPrompt);
            log.debug("User prompt ({} chars):\n{}", userPrompt.length(), userPrompt);
        }

        // Build effective tool list -- inject delegation tool when allowed
        List<Object> effectiveTools = buildEffectiveTools(agent, delegationContext);

        log.info(
                "Agent '{}' executing task | Tools: {} | AllowDelegation: {}",
                agent.getRole(),
                effectiveTools.size(),
                agent.isAllowDelegation());

        // Resolve tools
        ToolResolver.ResolvedTools resolvedTools = ToolResolver.resolve(effectiveTools);
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

        // Structured output parsing and retry loop (only when task has outputType set)
        Object parsedOutput = null;
        if (task.getOutputType() != null) {
            parsedOutput = StructuredOutputHandler.parse(agent, task, finalResponse, systemPrompt);
        }

        // Run output guardrails after response (and after structured output parsing)
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

        // Record this output in memory (no-op when memory is disabled)
        executionContext.memoryContext().record(output);

        return output;
    }

    /**
     * Build the effective tool list for this execution.
     *
     * When the agent has {@code allowDelegation = true} and a non-null
     * {@code delegationContext} is provided, an {@link AgentDelegationTool} is prepended
     * to the agent's configured tools.
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

        while (true) {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(resolvedTools.allSpecifications())
                    .build();

            ChatResponse response = agent.getLlm().chat(request);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    // Check limit before incrementing so toolCallCount reflects executed calls only
                    if (toolCallCounter.get() >= maxIterations) {
                        stopMessageCount++;
                        String stopText = "STOP: Maximum tool iterations (" + maxIterations
                                + ") reached. You must provide your best final answer now "
                                + "based on information gathered so far.";

                        if (stopMessageCount >= MAX_STOP_MESSAGES) {
                            throw new MaxIterationsExceededException(
                                    agent.getRole(), task.getDescription(), maxIterations, toolCallCounter.get());
                        }

                        log.warn(
                                "Agent '{}' exceeded max iterations ({}). Stop message sent ({}/{}).",
                                agent.getRole(),
                                maxIterations,
                                stopMessageCount,
                                MAX_STOP_MESSAGES);
                        messages.add(new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), stopText));
                    } else {
                        // Execute the tool and count only executed calls
                        toolCallCounter.incrementAndGet();
                        Instant toolStart = Instant.now();
                        String toolResult = resolvedTools.execute(toolRequest);
                        Duration toolDuration = Duration.between(toolStart, Instant.now());

                        if (toolResult != null && toolResult.startsWith("Error:")) {
                            log.warn(
                                    "Tool error: {}({}) -> {} [{}ms]",
                                    toolRequest.name(),
                                    truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                                    truncate(toolResult, LOG_TRUNCATE_LENGTH),
                                    toolDuration.toMillis());
                        } else {
                            log.info(
                                    "Tool call: {}({}) -> {} [{}ms]",
                                    toolRequest.name(),
                                    truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                                    truncate(toolResult, LOG_TRUNCATE_LENGTH),
                                    toolDuration.toMillis());
                        }

                        // Fire ToolCallEvent to all registered listeners
                        executionContext.fireToolCall(new ToolCallEvent(
                                toolRequest.name(),
                                toolRequest.arguments(),
                                toolResult,
                                agent.getRole(),
                                toolDuration));

                        messages.add(new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), toolResult));
                    }
                }
                // Continue the loop for the next LLM response
            } else {
                // LLM produced a text response -- we're done
                return aiMessage.text();
            }
        }
    }

    // ========================
    // Guardrail invocation
    // ========================

    /**
     * Evaluate all input guardrails on the task before the LLM call.
     *
     * Guardrails are evaluated in order. The first failure stops evaluation and throws
     * {@link GuardrailViolationException} with type INPUT.
     *
     * @param task           the task being executed
     * @param contextOutputs outputs from prior tasks passed as context
     * @throws GuardrailViolationException if any input guardrail fails
     */
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

    /**
     * Evaluate all output guardrails on the agent response after the LLM call.
     *
     * Guardrails are evaluated in order. The first failure stops evaluation and throws
     * {@link GuardrailViolationException} with type OUTPUT.
     *
     * @param task         the task that was executed
     * @param rawResponse  the raw text response from the agent
     * @param parsedOutput the parsed structured output, or {@code null} if not applicable
     * @throws GuardrailViolationException if any output guardrail fails
     */
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

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
