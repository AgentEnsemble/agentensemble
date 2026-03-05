package net.agentensemble.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentDelegationToolTest {

    private ChatModel model;
    private Agent researcher;
    private Agent writer;
    private AgentExecutor executor;
    private ExecutionContext executionContext;
    private DelegationContext delegationContext;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);

        // Default: model returns a plain text response so AgentExecutor won't loop
        ChatResponse response = mock(ChatResponse.class);
        AiMessage aiMessage = mock(AiMessage.class);
        when(response.aiMessage()).thenReturn(aiMessage);
        when(aiMessage.text()).thenReturn("delegated result");
        when(aiMessage.hasToolExecutionRequests()).thenReturn(false);
        when(model.chat(any(ChatRequest.class))).thenReturn(response);

        researcher = Agent.builder()
                .role("Researcher")
                .goal("Research things")
                .llm(model)
                .allowDelegation(true)
                .build();

        writer = Agent.builder()
                .role("Writer")
                .goal("Write things")
                .llm(model)
                .allowDelegation(true)
                .build();

        executor = mock(AgentExecutor.class);
        executionContext = ExecutionContext.disabled();
    }

    private TaskOutput makeOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("delegated task")
                .agentRole("Writer")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(100))
                .toolCallCount(0)
                .build();
    }

    // ========================
    // Successful delegation
    // ========================

    @Test
    void delegate_returnsWorkerOutput() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("research complete"));

        delegationContext = DelegationContext.create(List.of(researcher, writer), 3, executionContext, executor);

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        String result = tool.delegate("Writer", "Write a blog post about AI");

        assertThat(result).isEqualTo("research complete");
    }

    @Test
    void delegate_caseInsensitiveRoleMatch() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        String result = tool.delegate("writer", "Write something"); // lowercase

        assertThat(result).isEqualTo("output");
    }

    @Test
    void delegate_accumulates_delegatedOutputs() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output 1"))
                .thenReturn(makeOutput("output 2"));

        delegationContext = DelegationContext.create(List.of(researcher, writer), 3, executionContext, executor);

        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        tool.delegate("Researcher", "Task 1");
        tool.delegate("Writer", "Task 2");

        assertThat(tool.getDelegatedOutputs()).hasSize(2);
        assertThat(tool.getDelegatedOutputs().get(0).getRaw()).isEqualTo("output 1");
        assertThat(tool.getDelegatedOutputs().get(1).getRaw()).isEqualTo("output 2");
    }

    @Test
    void getDelegatedOutputs_returnsImmutableList() {
        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);

        List<TaskOutput> outputs = tool.getDelegatedOutputs();
        assertThat(outputs).isEmpty();
        // UnsupportedOperationException or similar expected on mutation attempt
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> outputs.add(makeOutput("x")));
    }

    // ========================
    // Agent not found
    // ========================

    @Test
    void delegate_agentNotFound_returnsErrorMessage() {
        delegationContext = DelegationContext.create(List.of(researcher), 3, executionContext, executor);

        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        String result = tool.delegate("NonExistentAgent", "Some task");

        assertThat(result).contains("NonExistentAgent");
        assertThat(result).containsIgnoringCase("not found");
    }

    @Test
    void delegate_agentNotFound_includesAvailableRoles() {
        delegationContext = DelegationContext.create(List.of(researcher, writer), 3, executionContext, executor);

        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        String result = tool.delegate("Unknown", "task");

        assertThat(result).contains("Researcher");
        assertThat(result).contains("Writer");
    }

    @Test
    void delegate_nullRole_returnsErrorMessage() {
        delegationContext = DelegationContext.create(List.of(researcher), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);

        String result = tool.delegate(null, "some task");
        // Null role is now caught early and returns a descriptive error before agent lookup
        assertThat(result).containsIgnoringCase("null or blank");
    }

    // ========================
    // Depth limit enforcement
    // ========================

    @Test
    void delegate_atDepthLimit_returnsLimitErrorMessage() {
        // Create a context already at the limit (depth = max)
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1, executionContext, executor)
                .descend(); // depth = 1 = max

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        String result = tool.delegate("Writer", "Write something");

        assertThat(result).containsIgnoringCase("delegation depth limit");
    }

    @Test
    void delegate_atDepthLimit_doesNotInvokeExecutor() {
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1, executionContext, executor)
                .descend();

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        tool.delegate("Writer", "Task");

        // No interactions with the executor
        org.mockito.Mockito.verifyNoInteractions(executor);
    }

    @Test
    void delegate_atDepthLimit_doesNotAccumulateOutputs() {
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1, executionContext, executor)
                .descend();

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        tool.delegate("Writer", "Task");

        assertThat(tool.getDelegatedOutputs()).isEmpty();
    }

    // ========================
    // Self-delegation guard
    // ========================

    @Test
    void delegate_toSelf_returnsErrorMessage() {
        delegationContext = DelegationContext.create(List.of(researcher, writer), 3, executionContext, executor);

        // The calling agent's role is "Researcher" -- delegates to itself
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        String result = tool.delegate("Researcher", "Do my own task");

        assertThat(result).containsIgnoringCase("cannot delegate to yourself");
    }

    // ========================
    // Descend context passed through
    // ========================

    @Test
    void delegate_passesDescendedContextToExecutor() {
        TaskOutput output = makeOutput("output");
        DelegationContext[] capturedContext = {null};

        // Custom executor that captures the DelegationContext passed to it
        AgentExecutor capturingExecutor = new AgentExecutor() {
            @Override
            public TaskOutput execute(
                    Task task, List<TaskOutput> contextOutputs, ExecutionContext ctx, DelegationContext dc) {
                capturedContext[0] = dc;
                return output;
            }
        };

        DelegationContext rootCtx = DelegationContext.create(List.of(writer), 3, executionContext, capturingExecutor);

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", rootCtx);
        tool.delegate("Writer", "Write something");

        assertThat(capturedContext[0]).isNotNull();
        assertThat(capturedContext[0].getCurrentDepth()).isEqualTo(1);
    }

    // ========================
    // DelegationResponse tests
    // ========================

    @Test
    void getDelegationResponses_emptyBeforeDelegation() {
        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);

        assertThat(tool.getDelegationResponses()).isEmpty();
    }

    @Test
    void delegate_successfulDelegation_producesDelegationResponse() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("writer result"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses()).hasSize(1);
    }

    @Test
    void delegate_successfulDelegation_responseHasSuccessStatus() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("writer result"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    @Test
    void delegate_successfulDelegation_responseContainsRawOutput() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("writer result"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses().get(0).rawOutput()).isEqualTo("writer result");
    }

    @Test
    void delegate_successfulDelegation_responseContainsWorkerRole() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("writer result"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses().get(0).workerRole()).isEqualTo("Writer");
    }

    @Test
    void delegate_successfulDelegation_responseHasNonNullTaskId() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses().get(0).taskId()).isNotNull().isNotBlank();
    }

    @Test
    void delegate_successfulDelegation_responseHasPositiveDuration() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses().get(0).duration()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void delegate_depthLimitBlocked_producesFailureDelegationResponse() {
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1, executionContext, executor)
                .descend();

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        tool.delegate("Writer", "Write something");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        assertThat(tool.getDelegationResponses().get(0).errors()).isNotEmpty();
    }

    @Test
    void delegate_selfDelegationBlocked_producesFailureDelegationResponse() {
        delegationContext = DelegationContext.create(List.of(researcher, writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("Researcher", "Self task");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
    }

    @Test
    void delegate_unknownAgent_producesFailureDelegationResponse() {
        delegationContext = DelegationContext.create(List.of(researcher), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        tool.delegate("UnknownAgent", "Task");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        assertThat(tool.getDelegationResponses().get(0).errors()).isNotEmpty();
    }

    @Test
    void delegate_multipleDelegations_allResponsesAccumulated() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output A"))
                .thenReturn(makeOutput("output B"));

        delegationContext = DelegationContext.create(List.of(researcher, writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        tool.delegate("Researcher", "Task A");
        tool.delegate("Writer", "Task B");

        assertThat(tool.getDelegationResponses()).hasSize(2);
        assertThat(tool.getDelegationResponses().get(0).rawOutput()).isEqualTo("output A");
        assertThat(tool.getDelegationResponses().get(1).rawOutput()).isEqualTo("output B");
    }

    @Test
    void getDelegationResponses_returnsImmutableList() {
        delegationContext = DelegationContext.create(List.of(writer), 3, executionContext, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);

        List<DelegationResponse> responses = tool.getDelegationResponses();
        assertThatThrownBy(() -> responses.add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
