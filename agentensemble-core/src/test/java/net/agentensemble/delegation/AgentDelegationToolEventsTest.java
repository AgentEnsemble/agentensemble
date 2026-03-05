package net.agentensemble.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.delegation.policy.DelegationPolicyResult;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentDelegationToolEventsTest {

    private Agent writer;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        writer = Agent.builder()
                .role("Writer")
                .goal("Write things")
                .llm(mock(dev.langchain4j.model.chat.ChatModel.class))
                .build();
        executor = mock(AgentExecutor.class);
    }

    private TaskOutput makeOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("task")
                .agentRole("Writer")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(50))
                .toolCallCount(0)
                .build();
    }

    private ExecutionContext contextWithListener(EnsembleListener listener) {
        return ExecutionContext.of(MemoryContext.disabled(), false, List.of(listener));
    }

    // ========================
    // Successful delegation fires start + completed events
    // ========================

    @Test
    void successfulDelegation_firesDelegationStartedEvent() {
        List<DelegationStartedEvent> started = new ArrayList<>();
        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                started.add(event);
            }
        });

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "Write a blog post");

        assertThat(started).hasSize(1);
        assertThat(started.get(0).workerRole()).isEqualTo("Writer");
        assertThat(started.get(0).delegatingAgentRole()).isEqualTo("Researcher");
        assertThat(started.get(0).taskDescription()).isEqualTo("Write a blog post");
        assertThat(started.get(0).delegationDepth()).isEqualTo(1);
    }

    @Test
    void successfulDelegation_firesDelegationCompletedEvent() {
        List<DelegationCompletedEvent> completed = new ArrayList<>();
        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationCompleted(DelegationCompletedEvent event) {
                completed.add(event);
            }
        });

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("written output"));

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "Write something");

        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).workerRole()).isEqualTo("Writer");
        assertThat(completed.get(0).delegatingAgentRole()).isEqualTo("Researcher");
        assertThat(completed.get(0).response().rawOutput()).isEqualTo("written output");
        assertThat(completed.get(0).response().status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    @Test
    void successfulDelegation_correlationIdMatchesStartAndCompleted() {
        List<String> startIds = new ArrayList<>();
        List<String> completedIds = new ArrayList<>();

        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                startIds.add(event.delegationId());
            }

            @Override
            public void onDelegationCompleted(DelegationCompletedEvent event) {
                completedIds.add(event.delegationId());
            }
        });

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(startIds).hasSize(1);
        assertThat(completedIds).hasSize(1);
        assertThat(startIds.get(0)).isEqualTo(completedIds.get(0));
        assertThat(startIds.get(0)).isNotBlank();
    }

    @Test
    void successfulDelegation_noFailedEventFired() {
        List<DelegationFailedEvent> failed = new ArrayList<>();
        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationFailed(DelegationFailedEvent event) {
                failed.add(event);
            }
        });

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(failed).isEmpty();
    }

    // ========================
    // Guard failures fire failed event (no start event)
    // ========================

    @Test
    void depthLimitGuard_firesDelegationFailedEvent_noStartEvent() {
        List<DelegationStartedEvent> started = new ArrayList<>();
        List<DelegationFailedEvent> failed = new ArrayList<>();

        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                started.add(event);
            }

            @Override
            public void onDelegationFailed(DelegationFailedEvent event) {
                failed.add(event);
            }
        });

        DelegationContext limitedCtx =
                DelegationContext.create(List.of(writer), 1, execCtx, executor).descend();

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", limitedCtx);
        tool.delegate("Writer", "task");

        assertThat(started).isEmpty();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).workerRole()).isEqualTo("Writer");
        assertThat(failed.get(0).cause()).isNull();
    }

    @Test
    void unknownAgent_firesDelegationFailedEvent_noStartEvent() {
        List<DelegationStartedEvent> started = new ArrayList<>();
        List<DelegationFailedEvent> failed = new ArrayList<>();

        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                started.add(event);
            }

            @Override
            public void onDelegationFailed(DelegationFailedEvent event) {
                failed.add(event);
            }
        });

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("UnknownRole", "task");

        assertThat(started).isEmpty();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).workerRole()).isEqualTo("UnknownRole");
        assertThat(failed.get(0).response().status()).isEqualTo(DelegationStatus.FAILURE);
    }

    // ========================
    // Policy rejection fires failed event (no start event)
    // ========================

    @Test
    void policyRejection_firesDelegationFailedEvent_noStartEvent() {
        List<DelegationStartedEvent> started = new ArrayList<>();
        List<DelegationFailedEvent> failed = new ArrayList<>();

        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                started.add(event);
            }

            @Override
            public void onDelegationFailed(DelegationFailedEvent event) {
                failed.add(event);
            }
        });

        DelegationPolicy rejectPolicy = (req, c) -> DelegationPolicyResult.reject("policy says no");
        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor, List.of(rejectPolicy));
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(started).isEmpty();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).failureReason()).contains("policy says no");
        assertThat(failed.get(0).cause()).isNull();
    }

    // ========================
    // Worker exception fires started + failed events
    // ========================

    @Test
    void workerException_firesBothStartedAndFailedEvents() {
        List<DelegationStartedEvent> started = new ArrayList<>();
        List<DelegationFailedEvent> failed = new ArrayList<>();

        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                started.add(event);
            }

            @Override
            public void onDelegationFailed(DelegationFailedEvent event) {
                failed.add(event);
            }
        });

        RuntimeException workerException = new RuntimeException("worker failed");
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenThrow(workerException);

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);

        assertThatThrownBy(() -> tool.delegate("Writer", "task")).isInstanceOf(RuntimeException.class);

        assertThat(started).hasSize(1);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).cause()).isSameAs(workerException);
        assertThat(failed.get(0).delegationId()).isEqualTo(started.get(0).delegationId());
    }

    // ========================
    // Listener exceptions are caught and don't abort delegation
    // ========================

    @Test
    void listenerException_doesNotAbortDelegation() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        ExecutionContext execCtx = contextWithListener(new EnsembleListener() {
            @Override
            public void onDelegationStarted(DelegationStartedEvent event) {
                throw new RuntimeException("listener bug");
            }
        });

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, execCtx, executor);
        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "task");

        assertThat(result).isEqualTo("output");
    }
}
