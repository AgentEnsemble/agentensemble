package net.agentensemble.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link TaskExecutor} using mocked LLMs to avoid real network calls.
 * Follows the same pattern as SequentialEnsembleIntegrationTest in agentensemble-core.
 */
class TaskExecutorTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private ChatModel mockModelWithResponse(String response) {
        var model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return model;
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_nullModelProvider_throwsNullPointer() {
        assertThatThrownBy(() -> new TaskExecutor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullToolProvider_throwsNullPointer() {
        var modelProvider = SimpleModelProvider.of(mock(ChatModel.class));

        assertThatThrownBy(() -> new TaskExecutor(modelProvider, null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // execute() validation
    // ========================

    @Test
    void execute_nullRequest_throwsNullPointer() {
        var executor = new TaskExecutor(SimpleModelProvider.of(mock(ChatModel.class)));

        assertThatThrownBy(() -> executor.execute((TaskRequest) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void execute_withConsumer_nullRequest_throwsNullPointer() {
        var executor = new TaskExecutor(SimpleModelProvider.of(mock(ChatModel.class)));

        assertThatThrownBy(() -> executor.execute(null, obj -> {})).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // execute() -- basic happy path
    // ========================

    @Test
    void execute_minimalRequest_returnsCompletedResult() {
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("AI is growing fast.")));

        var request = TaskRequest.builder()
                .description("Research the topic of AI")
                .expectedOutput("A research summary")
                .agent(AgentSpec.of("Researcher", "Research and summarize information"))
                .build();

        var result = executor.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.output()).isEqualTo("AI is growing fast.");
        assertThat(result.isComplete()).isTrue();
        assertThat(result.exitReason()).isEqualTo("COMPLETED");
    }

    @Test
    void execute_autoSynthesisNoAgent_returnsCompletedResult() {
        // No agent specified -- AgentEnsemble auto-synthesizes from the task description.
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("Synthesized output.")));

        var result = executor.execute(TaskRequest.of("Research AI trends", "A concise summary"));

        assertThat(result.output()).isEqualTo("Synthesized output.");
        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void execute_durationAndToolCallCountPopulated() {
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("Response.")));

        var result = executor.execute(TaskRequest.builder()
                .description("Summarize AI")
                .expectedOutput("A short summary")
                .agent(AgentSpec.of("Summarizer", "Summarize"))
                .build());

        // Duration is non-negative; tool call count is 0 when no tools used.
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.toolCallCount()).isGreaterThanOrEqualTo(0);
    }

    // ========================
    // execute() -- context and inputs
    // ========================

    @Test
    void execute_withContextAndInputs_runsSuccessfully() {
        // Context entries from upstream tasks and explicit inputs are both injected as
        // template variables. This test verifies the ensemble runs without error and
        // produces the mocked output.
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("Article complete.")));

        var request = TaskRequest.builder()
                .description("Write about {topic} based on: {research}")
                .expectedOutput("An article about {topic}")
                .agent(AgentSpec.of("Writer", "Write compelling content"))
                .context(Map.of("research", "AI is advancing rapidly in 2026."))
                .inputs(Map.of("topic", "Artificial Intelligence"))
                .build();

        var result = executor.execute(request);

        assertThat(result.output()).isEqualTo("Article complete.");
        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void execute_contextEntriesAloneWithNoExplicitInputs_runsSuccessfully() {
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("Context-only result.")));

        var request = TaskRequest.builder()
                .description("Summarize: {upstream}")
                .expectedOutput("A concise summary")
                .agent(AgentSpec.of("Summarizer", "Summarize information"))
                .context(Map.of("upstream", "Some upstream task output"))
                .build();

        assertThat(executor.execute(request).output()).isEqualTo("Context-only result.");
    }

    // ========================
    // execute() -- heartbeating
    // ========================

    @Test
    void execute_withHeartbeatConsumer_firesAtLeastTaskStartAndTaskCompleted() {
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("Research done.")));
        var heartbeats = new ArrayList<HeartbeatDetail>();

        var request = TaskRequest.builder()
                .description("Research AI")
                .expectedOutput("A research summary")
                .agent(AgentSpec.of("Researcher", "Research"))
                .build();

        executor.execute(request, obj -> heartbeats.add((HeartbeatDetail) obj));

        assertThat(heartbeats).isNotEmpty();
        var eventTypes = heartbeats.stream().map(HeartbeatDetail::eventType).toList();
        assertThat(eventTypes).contains("task_started", "task_completed");
    }

    @Test
    void execute_withNullConsumer_runsWithoutHeartbeating() {
        // Passing null consumer is explicitly supported and disables heartbeating.
        var executor = new TaskExecutor(SimpleModelProvider.of(mockModelWithResponse("No heartbeats.")));

        var result = executor.execute(
                TaskRequest.builder()
                        .description("Research something")
                        .expectedOutput("A research result")
                        .agent(AgentSpec.of("Researcher", "Research"))
                        .build(),
                null); // no heartbeating

        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // execute() -- model provider lookup
    // ========================

    @Test
    void execute_namedModelInRequest_usesNamedModelFromProvider() {
        var defaultModel = mockModelWithResponse("From default model.");
        var premiumModel = mockModelWithResponse("From premium model.");

        var provider = SimpleModelProvider.builder()
                .model("premium", premiumModel)
                .defaultModel(defaultModel)
                .build();

        var executor = new TaskExecutor(provider);

        var result = executor.execute(TaskRequest.builder()
                .description("Task using premium model")
                .expectedOutput("A high-quality result")
                .agent(AgentSpec.of("Agent", "Do work"))
                .modelName("premium")
                .build());

        assertThat(result.output()).isEqualTo("From premium model.");
    }

    @Test
    void execute_noModelNameInRequest_usesDefaultModel() {
        var defaultModel = mockModelWithResponse("From default model.");
        var provider = SimpleModelProvider.builder()
                .model("other", mockModelWithResponse("From other model."))
                .defaultModel(defaultModel)
                .build();

        var executor = new TaskExecutor(provider);

        var result = executor.execute(TaskRequest.builder()
                .description("Task with no model override")
                .expectedOutput("A result")
                .agent(AgentSpec.of("Agent", "Do work"))
                .build());

        assertThat(result.output()).isEqualTo("From default model.");
    }
}
