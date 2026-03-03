package net.agentensemble.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.delegation.AgentDelegationTool;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.LangChain4jToolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a single agent on a single task.
 *
 * Manages the ReAct-style tool-calling loop: the agent reasons, optionally calls
 * tools, incorporates results, and eventually produces a final text answer.
 *
 * When a {@link MemoryContext} is provided, relevant memories are injected into
 * the user prompt before execution and the task output is recorded into memory
 * after execution.
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
     * Execute the given task using the agent specified in the task, without memory.
     *
     * @param task           the task to execute
     * @param contextOutputs outputs from prior tasks to include as context
     * @param verbose        when true, prompts and responses are logged at INFO level
     * @return the task output
     * @throws AgentExecutionException        if the LLM throws an error
     * @throws MaxIterationsExceededException if the agent exceeds its iteration limit
     */
    public TaskOutput execute(Task task, List<TaskOutput> contextOutputs, boolean verbose) {
        return execute(task, contextOutputs, verbose, MemoryContext.disabled());
    }

    /**
     * Execute the given task using the agent specified in the task.
     *
     * When memoryContext is active, relevant memories are injected into the
     * user prompt before execution and the resulting TaskOutput is recorded
     * into memory after execution completes.
     *
     * @param task           the task to execute
     * @param contextOutputs outputs from prior tasks to include as context
     * @param verbose        when true, prompts and responses are logged at INFO level
     * @param memoryContext  runtime memory state; use {@link MemoryContext#disabled()}
     *                       when memory is not configured
     * @return the task output
     * @throws AgentExecutionException        if the LLM throws an error
     * @throws MaxIterationsExceededException if the agent exceeds its iteration limit
     */
    public TaskOutput execute(Task task, List<TaskOutput> contextOutputs, boolean verbose,
            MemoryContext memoryContext) {
        return execute(task, contextOutputs, verbose, memoryContext, null);
    }

    /**
     * Execute the given task using the agent specified in the task, with optional
     * agent delegation support.
     *
     * When {@code delegationContext} is non-null and the agent has
     * {@code allowDelegation = true}, an {@link AgentDelegationTool} is auto-injected
     * into the agent's effective tool list for this execution. The tool allows the agent
     * to delegate subtasks to peer agents during its ReAct loop.
     *
     * @param task              the task to execute
     * @param contextOutputs    outputs from prior tasks to include as context
     * @param verbose           when true, prompts and responses are logged at INFO level
     * @param memoryContext     runtime memory state; use {@link MemoryContext#disabled()}
     *                          when memory is not configured
     * @param delegationContext delegation state for this run; pass {@code null} when
     *                          delegation is not enabled for this ensemble
     * @return the task output
     * @throws AgentExecutionException        if the LLM throws an error
     * @throws MaxIterationsExceededException if the agent exceeds its iteration limit
     */
    public TaskOutput execute(Task task, List<TaskOutput> contextOutputs, boolean verbose,
            MemoryContext memoryContext, DelegationContext delegationContext) {
        // Normalize null memoryContext to disabled -- callers should prefer MemoryContext.disabled()
        // but defensive normalization here prevents NPE if null is passed directly.
        if (memoryContext == null) {
            memoryContext = MemoryContext.disabled();
        }

        Instant startTime = Instant.now();
        Agent agent = task.getAgent();
        boolean effectiveVerbose = verbose || agent.isVerbose();

        // Build prompts -- memory context injects STM, LTM, and entity knowledge as applicable
        String systemPrompt = AgentPromptBuilder.buildSystemPrompt(agent);
        String userPrompt = AgentPromptBuilder.buildUserPrompt(task, contextOutputs, memoryContext);

        if (effectiveVerbose) {
            log.info("System prompt:\n{}", systemPrompt);
            log.info("User prompt:\n{}", userPrompt);
        } else {
            log.debug("System prompt ({} chars):\n{}", systemPrompt.length(), systemPrompt);
            log.debug("User prompt ({} chars):\n{}", userPrompt.length(), userPrompt);
        }

        // Build effective tool list -- inject delegation tool when allowed
        List<Object> effectiveTools = buildEffectiveTools(agent, delegationContext);

        log.info("Agent '{}' executing task | Tools: {} | AllowDelegation: {}",
                agent.getRole(), effectiveTools.size(), agent.isAllowDelegation());

        // Resolve tools
        ResolvedTools resolvedTools = resolveTools(effectiveTools);
        AtomicInteger toolCallCounter = new AtomicInteger(0);

        String finalResponse;

        try {
            if (resolvedTools.hasTools()) {
                finalResponse = executeWithTools(agent, task, systemPrompt, userPrompt,
                        resolvedTools, toolCallCounter, effectiveVerbose);
            } else {
                finalResponse = executeWithoutTools(agent, systemPrompt, userPrompt);
                log.debug("Agent '{}' completed (no tools)", agent.getRole());
            }
        } catch (AgentExecutionException | MaxIterationsExceededException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentExecutionException(
                    "Agent '" + agent.getRole() + "' failed: " + e.getMessage(),
                    agent.getRole(), task.getDescription(), e);
        }

        if (finalResponse == null || finalResponse.isBlank()) {
            log.warn("Agent '{}' returned empty response for task '{}'",
                    agent.getRole(), truncate(task.getDescription(), 80));
            finalResponse = finalResponse != null ? finalResponse : "";
        }

        if (effectiveVerbose) {
            log.info("Agent response:\n{}", finalResponse);
        } else {
            log.trace("Full agent response:\n{}", finalResponse);
        }

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
                .build();

        // Record this output in memory (no-op when memory is disabled)
        memoryContext.record(output);

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
            AgentDelegationTool delegationTool =
                    new AgentDelegationTool(agent.getRole(), delegationContext);
            List<Object> tools = new ArrayList<>();
            tools.add(delegationTool);
            tools.addAll(agent.getTools());
            log.debug("Agent '{}' delegation tool injected (depth {}/{})",
                    agent.getRole(), delegationContext.getCurrentDepth(),
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
                .messages(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userPrompt)))
                .build();
        ChatResponse response = agent.getLlm().chat(request);
        return response.aiMessage().text();
    }

    private String executeWithTools(Agent agent, Task task, String systemPrompt, String userPrompt,
            ResolvedTools resolvedTools, AtomicInteger toolCallCounter, boolean verbose) {

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
                                    agent.getRole(), task.getDescription(),
                                    maxIterations, toolCallCounter.get());
                        }

                        log.warn("Agent '{}' exceeded max iterations ({}). Stop message sent ({}/{}).",
                                agent.getRole(), maxIterations, stopMessageCount, MAX_STOP_MESSAGES);
                        messages.add(new ToolExecutionResultMessage(
                                toolRequest.id(), toolRequest.name(), stopText));
                    } else {
                        // Execute the tool and count only executed calls
                        toolCallCounter.incrementAndGet();
                        Instant toolStart = Instant.now();
                        String toolResult = resolvedTools.execute(toolRequest);
                        long toolMs = Duration.between(toolStart, Instant.now()).toMillis();

                        if (toolResult != null && toolResult.startsWith("Error:")) {
                            log.warn("Tool error: {}({}) -> {} [{}ms]",
                                    toolRequest.name(),
                                    truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                                    truncate(toolResult, LOG_TRUNCATE_LENGTH),
                                    toolMs);
                        } else {
                            log.info("Tool call: {}({}) -> {} [{}ms]",
                                    toolRequest.name(),
                                    truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                                    truncate(toolResult, LOG_TRUNCATE_LENGTH),
                                    toolMs);
                        }

                        messages.add(new ToolExecutionResultMessage(
                                toolRequest.id(), toolRequest.name(), toolResult));
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
    // Tool resolution
    // ========================

    private ResolvedTools resolveTools(List<Object> tools) {
        Map<String, AgentTool> agentToolMap = new HashMap<>();
        Map<String, Object> annotatedObjectMap = new HashMap<>();
        List<ToolSpecification> allSpecs = new ArrayList<>();

        for (Object tool : tools) {
            if (tool instanceof AgentTool agentTool) {
                agentToolMap.put(agentTool.name(), agentTool);
                allSpecs.add(LangChain4jToolAdapter.toSpecification(agentTool));
            } else {
                // @Tool-annotated object
                List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(tool);
                for (ToolSpecification spec : specs) {
                    annotatedObjectMap.put(spec.name(), tool);
                }
                allSpecs.addAll(specs);
            }
        }

        return new ResolvedTools(agentToolMap, annotatedObjectMap, allSpecs);
    }

    // ========================
    // Inner helper class
    // ========================

    private record ResolvedTools(
            Map<String, AgentTool> agentToolMap,
            Map<String, Object> annotatedObjectMap,
            List<ToolSpecification> allSpecifications) {

        boolean hasTools() {
            return !agentToolMap.isEmpty() || !annotatedObjectMap.isEmpty();
        }

        String execute(ToolExecutionRequest request) {
            String toolName = request.name();

            // Check AgentTool map first
            AgentTool agentTool = agentToolMap.get(toolName);
            if (agentTool != null) {
                return LangChain4jToolAdapter.execute(agentTool, request.arguments());
            }

            // Check @Tool-annotated objects
            Object annotatedObj = annotatedObjectMap.get(toolName);
            if (annotatedObj != null) {
                return LangChain4jToolAdapter.executeAnnotatedTool(annotatedObj, toolName,
                        request.arguments());
            }

            return "Error: Unknown tool '" + toolName + "'";
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
