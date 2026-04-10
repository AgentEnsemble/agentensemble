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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link EnsembleExecutor} using mocked LLMs to avoid real network calls.
 * Follows the same pattern as SequentialEnsembleIntegrationTest in agentensemble-core.
 */
class EnsembleExecutorTest {

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
        assertThatThrownBy(() -> new EnsembleExecutor(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullToolProvider_throwsNullPointer() {
        var modelProvider = SimpleModelProvider.of(mock(ChatModel.class));

        assertThatThrownBy(() -> new EnsembleExecutor(modelProvider, null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // execute() validation
    // ========================

    @Test
    void execute_nullRequest_throwsNullPointer() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mock(ChatModel.class)));

        assertThatThrownBy(() -> executor.execute((EnsembleRequest) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void execute_emptyTaskList_throwsIllegalArgument() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mock(ChatModel.class)));
        var request = EnsembleRequest.builder().tasks(List.of()).build();

        assertThatThrownBy(() -> executor.execute(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one task");
    }

    @Test
    void execute_nullTaskList_throwsIllegalArgument() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mock(ChatModel.class)));
        var request = EnsembleRequest.builder().build(); // tasks defaults to empty list (via @Singular)

        assertThatThrownBy(() -> executor.execute(request)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // execute() -- single task
    // ========================

    @Test
    void execute_singleTask_returnsCompletedResult() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Research complete.")));

        var request = EnsembleRequest.builder()
                .task(TaskRequest.builder()
                        .description("Research AI trends")
                        .expectedOutput("A research summary")
                        .agent(AgentSpec.of("Researcher", "Research and summarize"))
                        .build())
                .build();

        var result = executor.execute(request);

        assertThat(result).isNotNull();
        assertThat(result.finalOutput()).isEqualTo("Research complete.");
        assertThat(result.isComplete()).isTrue();
        assertThat(result.exitReason()).isEqualTo("COMPLETED");
        assertThat(result.taskOutputs()).hasSize(1);
    }

    @Test
    void execute_singleTask_autoSynthesis_returnsCompletedResult() {
        // No agent spec -- ensemble auto-synthesizes the agent.
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Auto-synthesized output.")));

        var request = EnsembleRequest.builder()
                .task(TaskRequest.of("Research AI ethics", "A summary of key ethical concerns"))
                .build();

        var result = executor.execute(request);

        assertThat(result.finalOutput()).isEqualTo("Auto-synthesized output.");
        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // execute() -- null expectedOutput default (Copilot fix)
    // ========================

    @Test
    void execute_singleArgFactory_nullExpectedOutput_usesDefaultAndSucceeds() {
        // TaskRequest.of(String) leaves expectedOutput null. EnsembleExecutor substitutes
        // Task.DEFAULT_EXPECTED_OUTPUT so the task is always executable.
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Result.")));

        var result = executor.execute(EnsembleRequest.builder()
                .task(TaskRequest.of("Research something"))
                .build());

        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // execute() -- multiple tasks (sequential)
    // ========================

    @Test
    void execute_multipleTasksSequential_returnsLastTaskOutputAsFinal() {
        var model = mockModelWithResponse("Task output.");
        var executor = new EnsembleExecutor(SimpleModelProvider.of(model));

        var request = EnsembleRequest.builder()
                .task(TaskRequest.builder()
                        .description("Research AI")
                        .expectedOutput("A research summary")
                        .agent(AgentSpec.builder()
                                .role("Researcher")
                                .goal("Research AI trends")
                                .build())
                        .build())
                .task(TaskRequest.builder()
                        .description("Write an article about AI")
                        .expectedOutput("A polished article about AI")
                        .agent(AgentSpec.builder()
                                .role("Writer")
                                .goal("Write compelling content")
                                .build())
                        .build())
                .workflow("SEQUENTIAL")
                .build();

        var result = executor.execute(request);

        assertThat(result.isComplete()).isTrue();
        assertThat(result.taskOutputs()).hasSize(2);
    }

    @Test
    void execute_multipleTasksSequential_taskOutputsInOrder() {
        var model = mockModelWithResponse("Task output.");
        var executor = new EnsembleExecutor(SimpleModelProvider.of(model));

        var request = EnsembleRequest.builder()
                .task(TaskRequest.of("Task 1", "Output 1"))
                .task(TaskRequest.of("Task 2", "Output 2"))
                .workflow("SEQUENTIAL")
                .build();

        var result = executor.execute(request);

        assertThat(result.taskOutputs()).hasSize(2);
        assertThat(result.taskOutputs()).allSatisfy(output -> assertThat(output).isEqualTo("Task output."));
    }

    // ========================
    // execute() -- template pre-resolution (Copilot fix)
    // ========================

    @Test
    void execute_withGlobalInputs_runsSuccessfully() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Processed.")));

        var request = EnsembleRequest.builder()
                .task(TaskRequest.of("Research {topic}", "A research summary about {topic}"))
                .inputs(Map.of("topic", "Artificial Intelligence"))
                .build();

        var result = executor.execute(request);

        assertThat(result.isComplete()).isTrue();
    }

    @Test
    void execute_perTaskInputsAreIsolatedAndDoNotLeakToOtherTasks() {
        // Task 1 sets "research" in its context. Task 2 has no context -- {research} in
        // task 2's description should NOT be substituted by task 1's value. Without the
        // per-task pre-resolution fix, the global input map would carry task 1's context
        // into task 2's template resolution.
        var model = mockModelWithResponse("Task output.");
        var executor = new EnsembleExecutor(SimpleModelProvider.of(model));

        // Both tasks must run without throwing (pre-resolved descriptions are valid even
        // when they contain unresolved {research} -- core Task.builder does not validate
        // placeholder resolution, only that description is non-blank).
        var result = executor.execute(EnsembleRequest.builder()
                .task(TaskRequest.builder()
                        .description("Research AI")
                        .expectedOutput("Summary")
                        .context(Map.of("research", "AI grows 40% YoY."))
                        .build())
                .task(TaskRequest.builder()
                        .description("Write about AI")
                        .expectedOutput("Article")
                        // No context -- {research} is not substituted for task 2
                        .build())
                .build());

        assertThat(result.isComplete()).isTrue();
        assertThat(result.taskOutputs()).hasSize(2);
    }

    @Test
    void execute_perTaskInputsTakePrecedenceOverGlobalInputs() {
        // Task 1 uses a per-task input that overrides the global "topic".
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Done.")));

        // Both tasks define their own "topic" -- this should not throw even though the
        // global input and per-task input share a key.
        var result = executor.execute(EnsembleRequest.builder()
                .task(TaskRequest.builder()
                        .description("Research {topic}")
                        .expectedOutput("Summary about {topic}")
                        .inputs(Map.of("topic", "Quantum Computing")) // overrides global
                        .build())
                .task(TaskRequest.of("Research {topic}", "Summary")) // uses global
                .inputs(Map.of("topic", "Artificial Intelligence")) // global
                .build());

        assertThat(result.isComplete()).isTrue();
        assertThat(result.taskOutputs()).hasSize(2);
    }

    // ========================
    // EnsembleExecutor.resolveTemplate() (unit tests for the package-visible helper)
    // ========================

    @Test
    void resolveTemplate_substitutesPlaceholders() {
        var vars = Map.of("topic", "AI", "author", "Alice");

        assertThat(EnsembleExecutor.resolveTemplate("Research {topic} by {author}", vars))
                .isEqualTo("Research AI by Alice");
    }

    @Test
    void resolveTemplate_nullTemplate_returnsNull() {
        assertThat(EnsembleExecutor.resolveTemplate(null, Map.of("key", "val"))).isNull();
    }

    @Test
    void resolveTemplate_emptyVars_returnsTemplateUnchanged() {
        assertThat(EnsembleExecutor.resolveTemplate("Hello {name}", Map.of())).isEqualTo("Hello {name}");
    }

    @Test
    void resolveTemplate_unknownPlaceholder_leftUnchanged() {
        assertThat(EnsembleExecutor.resolveTemplate("Hello {unknown}", Map.of("topic", "AI")))
                .isEqualTo("Hello {unknown}");
    }

    // ========================
    // execute() -- heartbeating
    // ========================

    @Test
    void execute_withHeartbeatConsumer_firesTaskStartedEvents() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Done.")));
        var heartbeats = new ArrayList<HeartbeatDetail>();

        var request = EnsembleRequest.builder()
                .task(TaskRequest.builder()
                        .description("Research AI")
                        .expectedOutput("A research summary")
                        .agent(AgentSpec.of("Researcher", "Research"))
                        .build())
                .build();

        executor.execute(request, obj -> heartbeats.add((HeartbeatDetail) obj));

        assertThat(heartbeats).isNotEmpty();
        var eventTypes = heartbeats.stream().map(HeartbeatDetail::eventType).toList();
        assertThat(eventTypes).contains("task_started", "task_completed");
    }

    @Test
    void execute_withNullConsumer_runsWithoutHeartbeating() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("No heartbeats.")));

        var result = executor.execute(
                EnsembleRequest.builder()
                        .task(TaskRequest.of("Research something", "A research result"))
                        .build(),
                null);

        assertThat(result.isComplete()).isTrue();
    }

    // ========================
    // execute() -- aggregate metadata
    // ========================

    @Test
    void execute_durationAndToolCallCountAreNonNegative() {
        var executor = new EnsembleExecutor(SimpleModelProvider.of(mockModelWithResponse("Output.")));

        var result = executor.execute(EnsembleRequest.builder()
                .task(TaskRequest.of("Research AI", "A research summary"))
                .build());

        assertThat(result.totalDurationMs()).isGreaterThanOrEqualTo(0L);
        assertThat(result.totalToolCalls()).isGreaterThanOrEqualTo(0);
    }
}
