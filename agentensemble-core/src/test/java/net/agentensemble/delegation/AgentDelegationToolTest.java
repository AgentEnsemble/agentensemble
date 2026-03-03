package net.agentensemble.delegation;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDelegationToolTest {

    private ChatModel model;
    private Agent researcher;
    private Agent writer;
    private AgentExecutor executor;
    private MemoryContext memoryContext;
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
        memoryContext = MemoryContext.disabled();
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
        when(executor.execute(any(Task.class), any(), any(Boolean.class), any(MemoryContext.class),
                any(DelegationContext.class)))
                .thenReturn(makeOutput("research complete"));

        delegationContext = DelegationContext.create(List.of(researcher, writer), 3,
                memoryContext, executor, false);

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        String result = tool.delegate("Writer", "Write a blog post about AI");

        assertThat(result).isEqualTo("research complete");
    }

    @Test
    void delegate_caseInsensitiveRoleMatch() {
        when(executor.execute(any(Task.class), any(), any(Boolean.class), any(MemoryContext.class),
                any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        delegationContext = DelegationContext.create(List.of(writer), 3, memoryContext, executor, false);

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", delegationContext);
        String result = tool.delegate("writer", "Write something"); // lowercase

        assertThat(result).isEqualTo("output");
    }

    @Test
    void delegate_accumulates_delegatedOutputs() {
        when(executor.execute(any(Task.class), any(), any(Boolean.class), any(MemoryContext.class),
                any(DelegationContext.class)))
                .thenReturn(makeOutput("output 1"))
                .thenReturn(makeOutput("output 2"));

        delegationContext = DelegationContext.create(List.of(researcher, writer), 3,
                memoryContext, executor, false);

        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        tool.delegate("Researcher", "Task 1");
        tool.delegate("Writer", "Task 2");

        assertThat(tool.getDelegatedOutputs()).hasSize(2);
        assertThat(tool.getDelegatedOutputs().get(0).getRaw()).isEqualTo("output 1");
        assertThat(tool.getDelegatedOutputs().get(1).getRaw()).isEqualTo("output 2");
    }

    @Test
    void getDelegatedOutputs_returnsImmutableList() {
        delegationContext = DelegationContext.create(List.of(writer), 3, memoryContext, executor, false);
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
        delegationContext = DelegationContext.create(List.of(researcher), 3, memoryContext, executor, false);

        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        String result = tool.delegate("NonExistentAgent", "Some task");

        assertThat(result).contains("NonExistentAgent");
        assertThat(result).containsIgnoringCase("not found");
    }

    @Test
    void delegate_agentNotFound_includesAvailableRoles() {
        delegationContext = DelegationContext.create(List.of(researcher, writer), 3,
                memoryContext, executor, false);

        AgentDelegationTool tool = new AgentDelegationTool("Caller", delegationContext);
        String result = tool.delegate("Unknown", "task");

        assertThat(result).contains("Researcher");
        assertThat(result).contains("Writer");
    }

    @Test
    void delegate_nullRole_returnsErrorMessage() {
        delegationContext = DelegationContext.create(List.of(researcher), 3, memoryContext, executor, false);
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
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1,
                memoryContext, executor, false)
                .descend(); // depth = 1 = max

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        String result = tool.delegate("Writer", "Write something");

        assertThat(result).containsIgnoringCase("delegation depth limit");
    }

    @Test
    void delegate_atDepthLimit_doesNotInvokeExecutor() {
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1,
                memoryContext, executor, false)
                .descend();

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        tool.delegate("Writer", "Task");

        // No interactions with the executor
        org.mockito.Mockito.verifyNoInteractions(executor);
    }

    @Test
    void delegate_atDepthLimit_doesNotAccumulateOutputs() {
        DelegationContext limitedCtx = DelegationContext.create(List.of(writer), 1,
                memoryContext, executor, false)
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
        delegationContext = DelegationContext.create(List.of(researcher, writer), 3,
                memoryContext, executor, false);

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
            public TaskOutput execute(Task task, List<TaskOutput> contextOutputs,
                    boolean verbose, MemoryContext mc, DelegationContext dc) {
                capturedContext[0] = dc;
                return output;
            }
        };

        DelegationContext rootCtx = DelegationContext.create(List.of(writer), 3,
                memoryContext, capturingExecutor, false);

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", rootCtx);
        tool.delegate("Writer", "Write something");

        assertThat(capturedContext[0]).isNotNull();
        assertThat(capturedContext[0].getCurrentDepth()).isEqualTo(1);
    }
}
