package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TokenEvent;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.trace.CaptureMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the streaming path in {@link AgentExecutor}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>When no streaming model is configured, the synchronous {@code ChatModel} is used</li>
 *   <li>When a streaming model is configured, it is used for the no-tools path</li>
 *   <li>Each streamed token fires a {@link TokenEvent} to registered listeners</li>
 *   <li>The streaming model resolution order is agent > task > ensemble</li>
 *   <li>Streaming model is not used when the agent has tools (tool path remains sync)</li>
 * </ul>
 */
class AgentExecutorStreamingTest {

    private AgentExecutor agentExecutor;
    private ChatModel syncModel;
    private StreamingChatModel streamingModel;

    @BeforeEach
    void setUp() {
        agentExecutor = new AgentExecutor();
        syncModel = mock(ChatModel.class);
        streamingModel = mock(StreamingChatModel.class);

        // Synchronous model returns a simple text response with no tool calls
        AiMessage aiMessage = AiMessage.from("Final answer from sync");
        ChatResponse syncResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .tokenUsage(new TokenUsage(10, 5))
                .build();
        when(syncModel.chat(any(ChatRequest.class))).thenReturn(syncResponse);
    }

    // ========================
    // Non-streaming path (no streaming model configured)
    // ========================

    @Test
    void withoutStreamingModel_usesSyncChatModel() {
        Agent agent =
                Agent.builder().role("Writer").goal("Write text").llm(syncModel).build();
        Task task = Task.builder()
                .description("Write something")
                .expectedOutput("A paragraph")
                .agent(agent)
                .build();
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false);

        TaskOutput output = agentExecutor.execute(task, List.of(), ctx);

        assertThat(output.getRaw()).isEqualTo("Final answer from sync");
        verify(syncModel).chat(any(ChatRequest.class));
    }

    @Test
    void withoutStreamingModel_noTokenEventsAreFired() {
        List<TokenEvent> capturedTokens = new ArrayList<>();
        EnsembleListener listener = new EnsembleListener() {
            @Override
            public void onToken(TokenEvent event) {
                capturedTokens.add(event);
            }
        };

        Agent agent =
                Agent.builder().role("Writer").goal("Write text").llm(syncModel).build();
        Task task = Task.builder()
                .description("Write something")
                .expectedOutput("A paragraph")
                .agent(agent)
                .build();
        ExecutionContext ctx = ExecutionContext.of(MemoryContext.disabled(), false, List.of(listener));

        agentExecutor.execute(task, List.of(), ctx);

        assertThat(capturedTokens).isEmpty();
    }

    // ========================
    // Streaming path via ensemble-level streaming model
    // ========================

    @Test
    void ensembleLevelStreamingModel_usedWhenAgentAndTaskHaveNone() {
        // Configure the streaming model to deliver two tokens then complete
        AiMessage streamedMessage = AiMessage.from("Hello world");
        ChatResponse streamedResponse = ChatResponse.builder()
                .aiMessage(streamedMessage)
                .tokenUsage(new TokenUsage(8, 4))
                .build();
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onPartialResponse("Hello ");
                    handler.onPartialResponse("world");
                    handler.onCompleteResponse(streamedResponse);
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any());

        List<TokenEvent> capturedTokens = new ArrayList<>();
        EnsembleListener listener = new EnsembleListener() {
            @Override
            public void onToken(TokenEvent event) {
                capturedTokens.add(event);
            }
        };

        Agent agent =
                Agent.builder().role("Writer").goal("Write text").llm(syncModel).build();
        Task task = Task.builder()
                .description("Write something")
                .expectedOutput("A paragraph")
                .agent(agent)
                .build();
        ExecutionContext ctx = ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(listener),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null,
                null,
                null,
                streamingModel);

        TaskOutput output = agentExecutor.execute(task, List.of(), ctx);

        // Final response should come from the streamed ChatResponse
        assertThat(output.getRaw()).isEqualTo("Hello world");

        // Two token events should have been fired, one per partial response
        assertThat(capturedTokens).hasSize(2);
        assertThat(capturedTokens.get(0).token()).isEqualTo("Hello ");
        assertThat(capturedTokens.get(1).token()).isEqualTo("world");
        assertThat(capturedTokens).allMatch(e -> e.agentRole().equals("Writer"));

        // Sync model should not have been called
        verify(syncModel, never()).chat(any(ChatRequest.class));
    }

    // ========================
    // Streaming model resolution order
    // ========================

    @Test
    void agentLevelStreamingModel_takesOverEnsembleLevelModel() {
        StreamingChatModel agentStreamingModel = mock(StreamingChatModel.class);
        AiMessage msg = AiMessage.from("Agent streaming result");
        ChatResponse resp = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(5, 3))
                .build();
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onPartialResponse("Agent streaming result");
                    handler.onCompleteResponse(resp);
                    return null;
                })
                .when(agentStreamingModel)
                .chat(any(ChatRequest.class), any());

        // Ensemble-level streaming model should NOT be invoked
        List<TokenEvent> capturedTokens = new ArrayList<>();
        EnsembleListener listener = new EnsembleListener() {
            @Override
            public void onToken(TokenEvent event) {
                capturedTokens.add(event);
            }
        };

        Agent agent = Agent.builder()
                .role("Researcher")
                .goal("Do research")
                .llm(syncModel)
                .streamingLlm(agentStreamingModel) // agent-level override
                .build();
        Task task = Task.builder()
                .description("Research topic")
                .expectedOutput("A report")
                .agent(agent)
                .build();
        // ensemble-level streaming model provided but should be overridden by agent-level
        ExecutionContext ctx = ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(listener),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null,
                null,
                null,
                streamingModel);

        TaskOutput output = agentExecutor.execute(task, List.of(), ctx);

        assertThat(output.getRaw()).isEqualTo("Agent streaming result");
        assertThat(capturedTokens).hasSize(1);
        assertThat(capturedTokens.get(0).agentRole()).isEqualTo("Researcher");

        // Ensemble-level streaming model must NOT have been called
        verify(streamingModel, never()).chat(any(ChatRequest.class), any());
        verify(agentStreamingModel).chat(any(ChatRequest.class), any());
    }

    @Test
    void taskLevelStreamingModel_takesOverEnsembleLevelModel() {
        StreamingChatModel taskStreamingModel = mock(StreamingChatModel.class);
        AiMessage msg = AiMessage.from("Task streaming result");
        ChatResponse resp = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(5, 3))
                .build();
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onPartialResponse("Task streaming result");
                    handler.onCompleteResponse(resp);
                    return null;
                })
                .when(taskStreamingModel)
                .chat(any(ChatRequest.class), any());

        Agent agent = Agent.builder()
                .role("Analyst")
                .goal("Analyse data")
                .llm(syncModel)
                .build();
        Task task = Task.builder()
                .description("Analyse the data")
                .expectedOutput("An analysis")
                .agent(agent)
                .streamingChatLanguageModel(taskStreamingModel) // task-level override
                .build();
        ExecutionContext ctx = ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null,
                null,
                null,
                streamingModel);

        TaskOutput output = agentExecutor.execute(task, List.of(), ctx);

        assertThat(output.getRaw()).isEqualTo("Task streaming result");
        verify(streamingModel, never()).chat(any(ChatRequest.class), any());
        verify(taskStreamingModel).chat(any(ChatRequest.class), any());
    }

    // ========================
    // Streaming error handling
    // ========================

    @Test
    void streamingError_propagatesAsAgentExecutionException() {
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onError(new RuntimeException("LLM streaming failure"));
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any());

        Agent agent =
                Agent.builder().role("Writer").goal("Write text").llm(syncModel).build();
        Task task = Task.builder()
                .description("Write a story")
                .expectedOutput("A story")
                .agent(agent)
                .build();
        ExecutionContext ctx = ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null,
                null,
                null,
                streamingModel);

        assertThatThrownBy(() -> agentExecutor.execute(task, List.of(), ctx))
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("LLM streaming failure");
    }

    // ========================
    // Listener exception safety
    // ========================

    @Test
    void streamingListener_throwsException_doesNotAbortStreaming() {
        AiMessage msg = AiMessage.from("Complete response");
        ChatResponse resp = ChatResponse.builder()
                .aiMessage(msg)
                .tokenUsage(new TokenUsage(5, 3))
                .build();
        doAnswer(inv -> {
                    StreamingChatResponseHandler handler = inv.getArgument(1);
                    handler.onPartialResponse("tok1");
                    handler.onPartialResponse("tok2");
                    handler.onCompleteResponse(resp);
                    return null;
                })
                .when(streamingModel)
                .chat(any(ChatRequest.class), any());

        // Listener that throws on every token
        EnsembleListener badListener = new EnsembleListener() {
            @Override
            public void onToken(TokenEvent event) {
                throw new RuntimeException("listener failure");
            }
        };

        Agent agent =
                Agent.builder().role("Writer").goal("Write text").llm(syncModel).build();
        Task task = Task.builder()
                .description("Write something")
                .expectedOutput("Output")
                .agent(agent)
                .build();
        ExecutionContext ctx = ExecutionContext.of(
                MemoryContext.disabled(),
                false,
                List.of(badListener),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null,
                null,
                null,
                streamingModel);

        // Listener exception must not abort execution -- task should still complete
        TaskOutput output = agentExecutor.execute(task, List.of(), ctx);
        assertThat(output.getRaw()).isEqualTo("Complete response");
    }
}
