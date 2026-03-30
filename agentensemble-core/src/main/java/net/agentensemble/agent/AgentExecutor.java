package net.agentensemble.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.callback.LlmIterationCompletedEvent;
import net.agentensemble.callback.LlmIterationStartedEvent;
import net.agentensemble.callback.TokenEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.delegation.AgentDelegationTool;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.directive.Directive;
import net.agentensemble.directive.DirectiveStore;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailInput;
import net.agentensemble.guardrail.GuardrailOutput;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.guardrail.GuardrailViolationException.GuardrailType;
import net.agentensemble.guardrail.InputGuardrail;
import net.agentensemble.guardrail.OutputGuardrail;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryOperationListener;
import net.agentensemble.memory.MemoryRecord;
import net.agentensemble.memory.MemoryScope;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.reflection.TaskIdentity;
import net.agentensemble.reflection.TaskReflection;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.CapturedMessage;
import net.agentensemble.trace.LlmResponseType;
import net.agentensemble.trace.ToolCallOutcome;
import net.agentensemble.trace.ToolCallTrace;
import net.agentensemble.trace.internal.TaskTraceAccumulator;
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
 * <p>Execution metrics and a full call trace are captured on every execution. Both are
 * available via {@code TaskOutput.getMetrics()} and {@code TaskOutput.getTrace()}.
 *
 * <p>When the {@link ExecutionContext} has a {@link CaptureMode} of
 * {@link CaptureMode#STANDARD} or higher, the full LLM message history is captured per
 * iteration and memory operation counts are wired via a {@link MemoryOperationListener}.
 * At {@link CaptureMode#FULL}, tool arguments are additionally parsed into structured
 * {@code parsedInput} maps on each {@link ToolCallTrace}.
 *
 * <p>Stateless -- all state is held in local variables during execution.
 */
public class AgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutor.class);

    /** Number of "stop" messages to send before throwing MaxIterationsExceededException. */
    private static final int MAX_STOP_MESSAGES = 3;

    /** Truncation length for tool input/output in INFO logs. */
    private static final int LOG_TRUNCATE_LENGTH = 200;

    /** Jackson mapper used to parse tool arguments into structured maps at FULL capture. */
    private static final ObjectMapper ARGUMENT_MAPPER = new ObjectMapper();

    /** Type reference for parsing tool arguments as Map<String,Object>. */
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

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

        Instant startTime = Instant.now();
        Agent agent = task.getAgent();
        boolean verbose = executionContext.isVerbose();
        boolean effectiveVerbose = verbose || agent.isVerbose();
        CaptureMode captureMode = executionContext.captureMode();

        // Create the trace accumulator for this task execution
        TaskTraceAccumulator accumulator = new TaskTraceAccumulator(
                agent.getRole(), task.getDescription(), task.getExpectedOutput(), startTime, captureMode);

        // Wire memory operation listener for STANDARD+ so MemoryOperationCounts are populated
        if (captureMode.isAtLeast(CaptureMode.STANDARD)) {
            MemoryOperationListener memListener = new MemoryOperationListener() {
                @Override
                public void onStmWrite() {
                    accumulator.incrementStmWrite();
                }

                @Override
                public void onLtmStore() {
                    accumulator.incrementLtmStore();
                }

                @Override
                public void onLtmRetrieval(Duration duration) {
                    accumulator.incrementLtmRetrieval(duration);
                }

                @Override
                public void onEntityLookup(Duration duration) {
                    accumulator.incrementEntityLookup(duration);
                }
            };
            executionContext.memoryContext().setOperationListener(memListener);
        }

        try {
            runInputGuardrails(task, contextOutputs);

            // Load prior reflection if reflection is enabled on this task.
            // Done before prompt building so it can be injected into the user prompt.
            TaskReflection priorReflection = null;
            if (task.getReflectionConfig() != null && executionContext.reflectionStore() != null) {
                priorReflection = executionContext
                        .reflectionStore()
                        .retrieve(TaskIdentity.of(task))
                        .orElse(null);
            }

            // Retrieve active context directives for injection into the prompt
            List<Directive> activeDirectives = null;
            DirectiveStore ds = executionContext.directiveStore();
            if (ds != null) {
                List<Directive> fetched = ds.activeContextDirectives();
                if (!fetched.isEmpty()) {
                    activeDirectives = fetched;
                }
            }

            // Time prompt building
            Instant promptStart = Instant.now();
            String systemPrompt = AgentPromptBuilder.buildSystemPrompt(agent);
            String userPrompt = AgentPromptBuilder.buildUserPrompt(
                    task,
                    contextOutputs,
                    executionContext.memoryContext(),
                    executionContext.memoryStore(),
                    priorReflection,
                    activeDirectives);
            Duration promptBuildTime = Duration.between(promptStart, Instant.now());
            accumulator.recordPrompts(systemPrompt, userPrompt, promptBuildTime);

            if (effectiveVerbose) {
                log.info("System prompt:\n{}", systemPrompt);
                log.info("User prompt:\n{}", userPrompt);
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("System prompt ({} chars):\n{}", systemPrompt.length(), systemPrompt);
                }
                if (log.isDebugEnabled()) {
                    log.debug("User prompt ({} chars):\n{}", userPrompt.length(), userPrompt);
                }
            }

            List<Object> effectiveTools = buildEffectiveTools(agent, delegationContext, accumulator);

            if (log.isInfoEnabled()) {
                log.info(
                        "Agent '{}' executing task | Tools: {} | AllowDelegation: {}",
                        agent.getRole(),
                        effectiveTools.size(),
                        agent.isAllowDelegation());
            }

            // Build a file change listener that delegates to all registered ensemble listeners.
            // Capture executionContext in a final local because it may have been reassigned above.
            final ExecutionContext execCtxForListener = executionContext;
            net.agentensemble.callback.EnsembleListener fileChangeListener =
                    new net.agentensemble.callback.EnsembleListener() {
                        @Override
                        public void onFileChanged(net.agentensemble.callback.FileChangedEvent event) {
                            execCtxForListener.fireFileChanged(event);
                        }
                    };

            // Resolve tools, injecting ToolContext (including reviewHandler and fileChangeListener)
            // into AbstractAgentTool instances
            ToolResolver.ResolvedTools resolvedTools = ToolResolver.resolve(
                    effectiveTools,
                    executionContext.toolMetrics(),
                    executionContext.toolExecutor(),
                    executionContext.reviewHandler(),
                    fileChangeListener);
            AtomicInteger toolCallCounter = new AtomicInteger(0);

            String finalResponse;

            try {
                // Resolve the streaming model for this agent: agent-level > task-level > ensemble-level
                StreamingChatModel streamingModel = resolveStreamingModel(agent, task, executionContext);

                if (resolvedTools.hasTools()) {
                    finalResponse = executeWithTools(
                            agent,
                            task,
                            systemPrompt,
                            userPrompt,
                            resolvedTools,
                            toolCallCounter,
                            executionContext,
                            accumulator,
                            captureMode);
                } else {
                    finalResponse = executeWithoutTools(
                            agent,
                            systemPrompt,
                            userPrompt,
                            accumulator,
                            captureMode,
                            streamingModel,
                            executionContext,
                            task.getDescription());
                    if (log.isDebugEnabled()) {
                        log.debug("Agent '{}' completed (no tools)", agent.getRole());
                    }
                }
            } catch (AgentExecutionException | MaxIterationsExceededException | ExitEarlyException e) {
                // Re-throw framework-controlled exceptions without wrapping
                throw e;
            } catch (Exception e) {
                throw new AgentExecutionException(
                        "Agent '" + agent.getRole() + "' failed: " + e.getMessage(),
                        agent.getRole(),
                        task.getDescription(),
                        e);
            }

            if (finalResponse == null || finalResponse.isBlank()) {
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Agent '{}' returned empty response for task '{}'",
                            agent.getRole(),
                            truncate(task.getDescription(), 80));
                }
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

            Instant completedAt = Instant.now();
            Duration duration = Duration.between(startTime, completedAt);
            int toolCalls = toolCallCounter.get();
            if (log.isDebugEnabled()) {
                log.debug("Agent '{}' completed | Tool calls: {} | Duration: {}", agent.getRole(), toolCalls, duration);
            }

            // Freeze accumulated trace and metrics
            net.agentensemble.trace.TaskTrace taskTrace = accumulator.buildTrace(
                    finalResponse, parsedOutput, completedAt, executionContext.costConfiguration());
            net.agentensemble.metrics.TaskMetrics taskMetrics = taskTrace.getMetrics();

            TaskOutput output = TaskOutput.builder()
                    .raw(finalResponse)
                    .taskDescription(task.getDescription())
                    .agentRole(agent.getRole())
                    .completedAt(completedAt)
                    .duration(duration)
                    .toolCallCount(toolCalls)
                    .parsedOutput(parsedOutput)
                    .outputType(task.getOutputType())
                    .metrics(taskMetrics)
                    .trace(taskTrace)
                    .build();

            // Store output in task-declared memory scopes (v2.0.0 MemoryStore API)
            MemoryStore memStore = executionContext.memoryStore();
            if (memStore != null
                    && task.getMemoryScopes() != null
                    && !task.getMemoryScopes().isEmpty()) {
                storeInDeclaredScopes(task, output, memStore);
            }

            // Legacy record call: no-op when MemoryContext is disabled (default in v2.0.0)
            executionContext
                    .memoryContext()
                    .record(new MemoryRecord(
                            output.getRaw(),
                            output.getAgentRole(),
                            output.getTaskDescription(),
                            output.getCompletedAt()));

            return output;

        } finally {
            // Always clear the memory listener, even when an exception is thrown
            if (captureMode.isAtLeast(CaptureMode.STANDARD)) {
                executionContext.memoryContext().clearOperationListener();
            }
        }
    }

    /**
     * Store the task output into each declared memory scope and apply eviction if configured.
     *
     * @param task      the task with declared memory scopes
     * @param output    the completed task output
     * @param memStore  the memory store to write into
     */
    private static void storeInDeclaredScopes(Task task, TaskOutput output, MemoryStore memStore) {
        HashMap<String, String> metadata = new HashMap<>();
        if (output.getAgentRole() != null) {
            metadata.put(MemoryEntry.META_AGENT_ROLE, output.getAgentRole());
        }
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

    /**
     * Build the effective tool list for this execution.
     * When the agent has {@code allowDelegation = true} and a non-null
     * {@code delegationContext} is provided, an {@link AgentDelegationTool} is prepended.
     * The accumulator's delegation consumer is wired into the delegation tool so that
     * completed delegations are captured in the task trace.
     */
    private List<Object> buildEffectiveTools(
            Agent agent, DelegationContext delegationContext, TaskTraceAccumulator accumulator) {
        if (agent.isAllowDelegation() && delegationContext != null) {
            AgentDelegationTool delegationTool =
                    new AgentDelegationTool(agent.getRole(), delegationContext, accumulator::addDelegation);
            List<Object> tools = new ArrayList<>();
            tools.add(delegationTool);
            tools.addAll(agent.getTools());
            if (log.isDebugEnabled()) {
                log.debug(
                        "Agent '{}' delegation tool injected (depth {}/{})",
                        agent.getRole(),
                        delegationContext.getCurrentDepth(),
                        delegationContext.getMaxDepth());
            }
            return tools;
        }
        return agent.getTools();
    }

    // ========================
    // Execution paths
    // ========================

    /**
     * Resolve the effective streaming model for this agent.
     *
     * <p>Resolution order (first non-null wins):
     * {@code agent.streamingLlm} &gt; {@code task.streamingChatLanguageModel} &gt;
     * {@code executionContext.streamingChatModel()}.
     *
     * @return the resolved streaming model, or {@code null} when streaming is not configured
     */
    private static StreamingChatModel resolveStreamingModel(Agent agent, Task task, ExecutionContext ctx) {
        if (agent.getStreamingLlm() != null) {
            return agent.getStreamingLlm();
        }
        if (task.getStreamingChatLanguageModel() != null) {
            return task.getStreamingChatLanguageModel();
        }
        return ctx.streamingChatModel();
    }

    /**
     * Execute the final LLM call using a {@link StreamingChatModel}, firing a
     * {@link TokenEvent} to all registered listeners for each token received.
     *
     * <p>Blocks the calling thread until the streaming response completes.
     *
     * @param streamingModel the streaming model to use
     * @param request        the chat request
     * @param ctx            execution context (used to fire token events)
     * @param agentRole      the role of the agent (included in each token event)
     * @return the completed {@link ChatResponse}
     * @throws AgentExecutionException if the streaming future is interrupted or fails
     */
    private static ChatResponse executeStreaming(
            StreamingChatModel streamingModel,
            ChatRequest request,
            ExecutionContext ctx,
            String agentRole,
            String taskDescription) {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        streamingModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                ctx.fireToken(new TokenEvent(partialResponse, agentRole, taskDescription));
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentExecutionException(
                    "Streaming interrupted for agent '" + agentRole + "'", agentRole, null, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new AgentExecutionException(
                    "Streaming failed for agent '" + agentRole + "': " + cause.getMessage(), agentRole, null, e);
        }
    }

    /**
     * Execute the direct LLM-to-answer path (no tools).
     *
     * <p>When {@code streamingModel} is non-null, uses streaming and fires
     * {@link TokenEvent}s for each received token. Otherwise falls back to the
     * synchronous {@code ChatModel.chat()} call on the agent's configured LLM.
     */
    private String executeWithoutTools(
            Agent agent,
            String systemPrompt,
            String userPrompt,
            TaskTraceAccumulator accumulator,
            CaptureMode captureMode,
            StreamingChatModel streamingModel,
            ExecutionContext executionContext,
            String taskDescription) {
        List<ChatMessage> messages = List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt));
        ChatRequest request = ChatRequest.builder().messages(messages).build();

        // Fire LLM iteration started event (iteration 0 for the single-shot path)
        executionContext.fireLlmIterationStarted(
                new LlmIterationStartedEvent(agent.getRole(), taskDescription, 0, CapturedMessage.fromAll(messages)));

        Instant llmStart = Instant.now();
        accumulator.beginLlmCall(llmStart);
        ChatResponse response;
        if (streamingModel != null) {
            if (log.isDebugEnabled()) {
                log.debug("Agent '{}' using streaming model for final response", agent.getRole());
            }
            response = executeStreaming(streamingModel, request, executionContext, agent.getRole(), taskDescription);
        } else {
            response = agent.getLlm().chat(request);
        }
        accumulator.endLlmCall(Instant.now(), response.tokenUsage());

        // Fire LLM iteration completed event
        Duration llmLatency = Duration.between(llmStart, Instant.now());
        long inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        long outputTokens =
                response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;
        executionContext.fireLlmIterationCompleted(new LlmIterationCompletedEvent(
                agent.getRole(),
                taskDescription,
                0,
                "FINAL_ANSWER",
                response.aiMessage().text(),
                null,
                inputTokens,
                outputTokens,
                llmLatency));

        String text = response.aiMessage().text();
        // Snapshot messages at STANDARD+ -- includes system + user (+ assistant final answer)
        if (captureMode.isAtLeast(CaptureMode.STANDARD)) {
            // Include the assistant final answer message in the snapshot so
            // the trace contains the full turn: system -> user -> assistant
            List<ChatMessage> fullTurn = new ArrayList<>(messages);
            fullTurn.add(response.aiMessage());
            accumulator.setCurrentMessages(CapturedMessage.fromAll(fullTurn));
        }
        accumulator.finalizeIteration(LlmResponseType.FINAL_ANSWER, text);
        return text;
    }

    private String executeWithTools(
            Agent agent,
            Task task,
            String systemPrompt,
            String userPrompt,
            ToolResolver.ResolvedTools resolvedTools,
            AtomicInteger toolCallCounter,
            ExecutionContext executionContext,
            TaskTraceAccumulator accumulator,
            CaptureMode captureMode) {

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userPrompt));

        int stopMessageCount = 0;
        int maxIterations = agent.getMaxIterations();
        Executor toolExecutor = executionContext.toolExecutor();
        String agentRole = agent.getRole();
        String taskDescription = task.getDescription();
        int iterationIndex = 0;

        while (true) {
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(resolvedTools.allSpecifications())
                    .build();

            // Fire LLM iteration started event
            executionContext.fireLlmIterationStarted(new LlmIterationStartedEvent(
                    agentRole, taskDescription, iterationIndex, CapturedMessage.fromAll(messages)));

            // Time the LLM call
            Instant llmStart = Instant.now();
            accumulator.beginLlmCall(llmStart);
            ChatResponse response = agent.getLlm().chat(request);
            accumulator.endLlmCall(Instant.now(), response.tokenUsage());

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            // Fire LLM iteration completed event
            Duration llmLatency = Duration.between(llmStart, Instant.now());
            long inputTokens =
                    response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
            long outputTokens =
                    response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;
            String responseType = aiMessage.hasToolExecutionRequests() ? "TOOL_CALLS" : "FINAL_ANSWER";
            List<LlmIterationCompletedEvent.ToolCallRequest> iterToolRequests = aiMessage.hasToolExecutionRequests()
                    ? aiMessage.toolExecutionRequests().stream()
                            .map(tr -> new LlmIterationCompletedEvent.ToolCallRequest(tr.name(), tr.arguments()))
                            .toList()
                    : null;
            executionContext.fireLlmIterationCompleted(new LlmIterationCompletedEvent(
                    agentRole,
                    taskDescription,
                    iterationIndex,
                    responseType,
                    aiMessage.text(),
                    iterToolRequests,
                    inputTokens,
                    outputTokens,
                    llmLatency));
            iterationIndex++;

            // Snapshot messages at STANDARD+ before finalizing
            if (captureMode.isAtLeast(CaptureMode.STANDARD)) {
                accumulator.setCurrentMessages(CapturedMessage.fromAll(messages));
            }

            if (!aiMessage.hasToolExecutionRequests()) {
                // LLM produced a text response -- done
                String text = aiMessage.text();
                accumulator.finalizeIteration(LlmResponseType.FINAL_ANSWER, text);
                return text;
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
                        messages,
                        accumulator,
                        captureMode);
                if (resultMsg == null) {
                    // Over limit: stop message was added by executeSingleTool
                    stopMessageCount++;
                    if (stopMessageCount >= MAX_STOP_MESSAGES) {
                        accumulator.finalizeIteration(LlmResponseType.TOOL_CALLS, null);
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
                        stopMessageCount,
                        accumulator,
                        captureMode);
            }

            accumulator.finalizeIteration(LlmResponseType.TOOL_CALLS, null);
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
            List<ChatMessage> messages,
            TaskTraceAccumulator accumulator,
            CaptureMode captureMode) {

        if (toolCallCounter.get() >= maxIterations) {
            String stopText = buildStopText(maxIterations);
            messages.add(new ToolExecutionResultMessage(toolRequest.id(), toolRequest.name(), stopText));
            if (log.isWarnEnabled()) {
                log.warn(
                        "Agent '{}' exceeded max iterations ({}) on tool '{}'.",
                        agentRole,
                        maxIterations,
                        toolRequest.name());
            }
            ToolCallTrace skippedTrace = ToolCallTrace.builder()
                    .toolName(toolRequest.name())
                    .arguments(toolRequest.arguments() != null ? toolRequest.arguments() : "{}")
                    .result(stopText)
                    .startedAt(Instant.now())
                    .completedAt(Instant.now())
                    .duration(Duration.ZERO)
                    .outcome(ToolCallOutcome.SKIPPED_MAX_ITERATIONS)
                    .build();
            accumulator.addToolCallToCurrentIteration(skippedTrace);
            return null;
        }

        toolCallCounter.incrementAndGet();
        Instant toolStart = Instant.now();
        ToolResult toolResult = resolvedTools.execute(toolRequest, agentRole);
        Instant toolEnd = Instant.now();
        Duration toolDuration = Duration.between(toolStart, toolEnd);
        String toolResultText = toText(toolResult);

        ToolCallTrace.ToolCallTraceBuilder traceBuilder = ToolCallTrace.builder()
                .toolName(toolRequest.name())
                .arguments(toolRequest.arguments() != null ? toolRequest.arguments() : "{}")
                .result(toolResultText)
                .structuredOutput(toolResult != null ? toolResult.getStructuredOutput() : null)
                .startedAt(toolStart)
                .completedAt(toolEnd)
                .duration(toolDuration)
                .outcome(classifyOutcome(toolResult));

        // Enrich with parsedInput at FULL capture
        if (captureMode.isAtLeast(CaptureMode.FULL)) {
            String args = toolRequest.arguments();
            Map<String, Object> parsedInput = parseArguments(args);
            traceBuilder.parsedInput(parsedInput);
        }

        accumulator.addToolCallToCurrentIteration(traceBuilder.build());

        logToolCall(agentRole, toolRequest, toolResultText, toolDuration);
        String toolOutcome = classifyOutcomeString(toolResult);
        executionContext.fireToolCall(new ToolCallEvent(
                toolRequest.name(),
                toolRequest.arguments(),
                toolResultText,
                toolResult != null ? toolResult.getStructuredOutput() : null,
                agentRole,
                toolDuration,
                executionContext.currentTaskIndex(),
                toolOutcome));

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
            int stopMessageCount,
            TaskTraceAccumulator accumulator,
            CaptureMode captureMode) {

        if (log.isDebugEnabled()) {
            log.debug("Agent '{}' executing {} tools in parallel", agentRole, toolRequests.size());
        }

        // Pre-check per request whether we can execute it
        record PendingTool(ToolExecutionRequest request, boolean withinLimit) {}

        List<PendingTool> pending = new ArrayList<>();
        for (ToolExecutionRequest req : toolRequests) {
            // Atomically check-and-increment via getAndUpdate: the counter only
            // advances if the current value is below the limit (CAS loop).
            boolean withinLimit = toolCallCounter.getAndUpdate(c -> c < maxIterations ? c + 1 : c) < maxIterations;
            pending.add(new PendingTool(req, withinLimit));
        }

        // Launch all executable tools in parallel
        record ToolExecution(
                ToolExecutionRequest request,
                ToolResult result,
                Duration duration,
                Instant toolStart,
                Instant toolEnd,
                boolean withinLimit) {}

        List<CompletableFuture<ToolExecution>> futures = new ArrayList<>();
        for (PendingTool pt : pending) {
            if (pt.withinLimit()) {
                CompletableFuture<ToolExecution> future = CompletableFuture.supplyAsync(
                        () -> {
                            Instant start = Instant.now();
                            ToolResult result = resolvedTools.execute(pt.request(), agentRole);
                            Instant end = Instant.now();
                            Duration dur = Duration.between(start, end);
                            return new ToolExecution(pt.request(), result, dur, start, end, true);
                        },
                        toolExecutor);
                futures.add(future);
            } else {
                // Over limit: resolve immediately as a stop message
                Instant now = Instant.now();
                CompletableFuture<ToolExecution> immediate = CompletableFuture.completedFuture(
                        new ToolExecution(pt.request(), null, Duration.ZERO, now, now, false));
                futures.add(immediate);
            }
        }

        // Wait for all futures then process in order. A global timeout prevents
        // indefinite blocking when a user-provided tool has no built-in timeout.
        // If any tool threw ExitEarlyException, CompletableFuture wraps it in CompletionException.
        // Unwrap and re-throw so the workflow executor can assemble partial results.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(5, TimeUnit.MINUTES)
                    .join();
        } catch (java.util.concurrent.CompletionException ce) {
            if (ce.getCause() instanceof ExitEarlyException exitEarly) {
                throw exitEarly;
            }
            if (ce.getCause() instanceof java.util.concurrent.TimeoutException) {
                throw new AgentExecutionException(
                        "Parallel tool execution for agent '" + agentRole + "' timed out after 5 minutes",
                        agentRole,
                        task.getDescription(),
                        ce);
            }
            throw ce; // NOPMD PreserveStackTrace - direct re-throw preserves the full CompletionException chain
        }

        int updatedStopCount = stopMessageCount;
        for (CompletableFuture<ToolExecution> future : futures) {
            ToolExecution te = future.join();
            if (!te.withinLimit()) {
                String stopText = buildStopText(maxIterations);
                messages.add(new ToolExecutionResultMessage(
                        te.request().id(), te.request().name(), stopText));
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Agent '{}' exceeded max iterations ({}) on tool '{}'.",
                            agentRole,
                            maxIterations,
                            te.request().name());
                }

                ToolCallTrace skippedTrace = ToolCallTrace.builder()
                        .toolName(te.request().name())
                        .arguments(
                                te.request().arguments() != null ? te.request().arguments() : "{}")
                        .result(stopText)
                        .startedAt(te.toolStart())
                        .completedAt(te.toolEnd())
                        .duration(Duration.ZERO)
                        .outcome(ToolCallOutcome.SKIPPED_MAX_ITERATIONS)
                        .build();
                accumulator.addToolCallToCurrentIteration(skippedTrace);

                updatedStopCount++;
                if (updatedStopCount >= MAX_STOP_MESSAGES) {
                    accumulator.finalizeIteration(LlmResponseType.TOOL_CALLS, null);
                    throw new MaxIterationsExceededException(
                            agentRole, task.getDescription(), maxIterations, toolCallCounter.get());
                }
            } else {
                String toolResultText = toText(te.result());
                logToolCall(agentRole, te.request(), toolResultText, te.duration());

                ToolCallTrace.ToolCallTraceBuilder traceBuilder = ToolCallTrace.builder()
                        .toolName(te.request().name())
                        .arguments(
                                te.request().arguments() != null ? te.request().arguments() : "{}")
                        .result(toolResultText)
                        .structuredOutput(te.result() != null ? te.result().getStructuredOutput() : null)
                        .startedAt(te.toolStart())
                        .completedAt(te.toolEnd())
                        .duration(te.duration())
                        .outcome(classifyOutcome(te.result()));

                // Enrich with parsedInput at FULL capture
                if (captureMode.isAtLeast(CaptureMode.FULL)) {
                    String args = te.request().arguments();
                    traceBuilder.parsedInput(parseArguments(args));
                }

                accumulator.addToolCallToCurrentIteration(traceBuilder.build());

                String parallelOutcome = classifyOutcomeString(te.result());
                executionContext.fireToolCall(new ToolCallEvent(
                        te.request().name(),
                        te.request().arguments(),
                        toolResultText,
                        te.result() != null ? te.result().getStructuredOutput() : null,
                        agentRole,
                        te.duration(),
                        executionContext.currentTaskIndex(),
                        parallelOutcome));
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
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Input guardrail blocked agent '{}' task '{}': {}",
                            agent.getRole(),
                            truncate(task.getDescription(), 80),
                            result.getMessage());
                }
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
                if (log.isWarnEnabled()) {
                    log.warn(
                            "Output guardrail blocked agent '{}' task '{}': {}",
                            agent.getRole(),
                            truncate(task.getDescription(), 80),
                            result.getMessage());
                }
                throw new GuardrailViolationException(
                        GuardrailType.OUTPUT, result.getMessage(), task.getDescription(), agent.getRole());
            }
        }
    }

    // ========================
    // Private utilities
    // ========================

    private static ToolCallOutcome classifyOutcome(ToolResult result) {
        if (result == null) {
            return ToolCallOutcome.ERROR;
        }
        return result.isSuccess() ? ToolCallOutcome.SUCCESS : ToolCallOutcome.FAILURE;
    }

    /**
     * Classify the tool execution outcome as a string for {@link ToolCallEvent#outcome()}.
     *
     * @param result the tool result; may be null
     * @return {@link ToolCallEvent#OUTCOME_SUCCESS} or {@link ToolCallEvent#OUTCOME_FAILURE}
     */
    private static String classifyOutcomeString(ToolResult result) {
        if (result == null || !result.isSuccess()) {
            return ToolCallEvent.OUTCOME_FAILURE;
        }
        return ToolCallEvent.OUTCOME_SUCCESS;
    }

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
            if (log.isWarnEnabled()) {
                log.warn(
                        "[{}] Tool error: {}({}) -> {} [{}ms]",
                        agentRole,
                        toolRequest.name(),
                        truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                        truncate(toolResultText, LOG_TRUNCATE_LENGTH),
                        toolDuration.toMillis());
            }
        } else {
            if (log.isInfoEnabled()) {
                log.info(
                        "[{}] Tool call: {}({}) -> {} [{}ms]",
                        agentRole,
                        toolRequest.name(),
                        truncate(toolRequest.arguments(), LOG_TRUNCATE_LENGTH),
                        truncate(toolResultText, LOG_TRUNCATE_LENGTH),
                        toolDuration.toMillis());
            }
        }
    }

    /**
     * Parse a JSON arguments string into a structured {@code Map<String, Object>}.
     *
     * <p>Used when {@link CaptureMode#FULL} is active to populate {@link ToolCallTrace#getParsedInput()}.
     * Returns {@code null} when the arguments string is null, blank, or cannot be parsed as JSON.
     * Failures are silently swallowed to ensure capture enrichment never disrupts execution.
     *
     * @param arguments the raw JSON arguments string from the LLM; may be {@code null}
     * @return the parsed map, or an empty map if arguments is null, blank, or cannot be parsed
     */
    private static Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return ARGUMENT_MAPPER.readValue(arguments, MAP_TYPE_REF);
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Could not parse tool arguments as JSON map for enriched trace: {}", e.getMessage());
            }
            return Collections.emptyMap();
        }
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
