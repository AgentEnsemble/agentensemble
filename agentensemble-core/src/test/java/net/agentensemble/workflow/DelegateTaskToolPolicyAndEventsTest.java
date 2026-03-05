package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.DelegationStatus;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.delegation.policy.DelegationPolicyResult;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegateTaskToolPolicyAndEventsTest {

    private Agent analyst;
    private AgentExecutor executor;

    @BeforeEach
    void setUp() {
        analyst = Agent.builder()
                .role("Analyst")
                .goal("Analyse things")
                .llm(mock(dev.langchain4j.model.chat.ChatModel.class))
                .build();
        executor = mock(AgentExecutor.class);
    }

    private TaskOutput makeOutput(String raw) {
        return TaskOutput.builder()
                .raw(raw)
                .taskDescription("task")
                .agentRole("Analyst")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(50))
                .toolCallCount(0)
                .build();
    }

    private ExecutionContext contextWithListener(EnsembleListener listener) {
        return ExecutionContext.of(MemoryContext.disabled(), false, List.of(listener));
    }

    // ========================
    // Policy evaluation
    // ========================

    @Test
    void rejectPolicy_preventsWorkerExecution() {
        DelegationContext ctx = DelegationContext.create(
                List.of(analyst),
                3,
                ExecutionContext.disabled(),
                executor,
                List.of((req, c) -> DelegationPolicyResult.reject("no delegation to Analyst")));

        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, ExecutionContext.disabled(), ctx);
        tool.delegateTask("Analyst", "Analyse data");

        verifyNoInteractions(executor);
    }

    @Test
    void rejectPolicy_returnsRejectionMessage() {
        DelegationContext ctx = DelegationContext.create(
                List.of(analyst),
                3,
                ExecutionContext.disabled(),
                executor,
                List.of((req, c) -> DelegationPolicyResult.reject("scope not defined")));

        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, ExecutionContext.disabled(), ctx);
        String result = tool.delegateTask("Analyst", "Analyse data");

        assertThat(result).contains("scope not defined");
    }

    @Test
    void rejectPolicy_producesFailureResponse() {
        DelegationContext ctx = DelegationContext.create(
                List.of(analyst),
                3,
                ExecutionContext.disabled(),
                executor,
                List.of((req, c) -> DelegationPolicyResult.reject("denied")));

        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, ExecutionContext.disabled(), ctx);
        tool.delegateTask("Analyst", "task");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
    }

    @Test
    void allowPolicy_workerExecutes() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("analysis done"));

        DelegationContext ctx = DelegationContext.create(
                List.of(analyst),
                3,
                ExecutionContext.disabled(),
                executor,
                List.of((req, c) -> DelegationPolicyResult.allow()));

        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, ExecutionContext.disabled(), ctx);
        String result = tool.delegateTask("Analyst", "Analyse Q3");

        assertThat(result).isEqualTo("analysis done");
    }

    @Test
    void modifyPolicy_changesAgentRoleToUnknown_returnsErrorAndNoWorkerExecution() {
        DelegationContext ctx = DelegationContext.create(
                List.of(analyst), 3, ExecutionContext.disabled(), executor, List.of((req, c) -> {
                    var modified = req.toBuilder().agentRole("NonExistent").build();
                    return DelegationPolicyResult.modify(modified);
                }));

        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, ExecutionContext.disabled(), ctx);
        String result = tool.delegateTask("Analyst", "task");

        assertThat(result).containsIgnoringCase("NonExistent");
        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        verifyNoInteractions(executor);
    }

    @Test
    void modifyPolicy_replacesRequest_workerInvokedNormally() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(
                List.of(analyst), 3, ExecutionContext.disabled(), executor, List.of((req, c) -> {
                    var enriched = req.toBuilder()
                            .taskDescription("Enriched: " + req.getTaskDescription())
                            .build();
                    return DelegationPolicyResult.modify(enriched);
                }));

        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, ExecutionContext.disabled(), ctx);
        String result = tool.delegateTask("Analyst", "original");

        assertThat(result).isEqualTo("output");
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    // ========================
    // Event firing
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

        DelegationContext ctx = DelegationContext.create(List.of(analyst), 3, execCtx, executor);
        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, execCtx, ctx);
        tool.delegateTask("Analyst", "Analyse data");

        assertThat(started).hasSize(1);
        assertThat(started.get(0).workerRole()).isEqualTo("Analyst");
        assertThat(started.get(0).delegatingAgentRole()).isEqualTo("Manager");
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
                .thenReturn(makeOutput("analysis result"));

        DelegationContext ctx = DelegationContext.create(List.of(analyst), 3, execCtx, executor);
        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, execCtx, ctx);
        tool.delegateTask("Analyst", "Analyse data");

        assertThat(completed).hasSize(1);
        assertThat(completed.get(0).response().rawOutput()).isEqualTo("analysis result");
        assertThat(completed.get(0).response().status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    @Test
    void successfulDelegation_correlationIdMatches() {
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

        DelegationContext ctx = DelegationContext.create(List.of(analyst), 3, execCtx, executor);
        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, execCtx, ctx);
        tool.delegateTask("Analyst", "task");

        assertThat(startIds.get(0)).isEqualTo(completedIds.get(0));
        assertThat(startIds.get(0)).isNotBlank();
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

        DelegationContext ctx = DelegationContext.create(List.of(analyst), 3, execCtx, executor);
        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, execCtx, ctx);
        tool.delegateTask("UnknownRole", "task");

        assertThat(started).isEmpty();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).workerRole()).isEqualTo("UnknownRole");
    }

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

        DelegationPolicy rejectPolicy = (req, c) -> DelegationPolicyResult.reject("no Analyst delegation");
        DelegationContext ctx = DelegationContext.create(List.of(analyst), 3, execCtx, executor, List.of(rejectPolicy));
        DelegateTaskTool tool = new DelegateTaskTool(List.of(analyst), executor, execCtx, ctx);
        tool.delegateTask("Analyst", "task");

        assertThat(started).isEmpty();
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).failureReason()).contains("no Analyst delegation");
        assertThat(failed.get(0).cause()).isNull();
    }
}
