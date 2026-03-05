package net.agentensemble.delegation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.delegation.AgentDelegationTool;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.DelegationStatus;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentDelegationToolPolicyTest {

    private Agent writer;
    private Agent analyst;
    private AgentExecutor executor;
    private ExecutionContext executionContext;

    @BeforeEach
    void setUp() {
        writer = Agent.builder()
                .role("Writer")
                .goal("Write things")
                .llm(mock(dev.langchain4j.model.chat.ChatModel.class))
                .build();
        analyst = Agent.builder()
                .role("Analyst")
                .goal("Analyse things")
                .llm(mock(dev.langchain4j.model.chat.ChatModel.class))
                .build();
        executor = mock(AgentExecutor.class);
        executionContext = ExecutionContext.disabled();
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

    // ========================
    // ALLOW policy permits delegation
    // ========================

    @Test
    void allowPolicy_permitsWorkerExecution() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("written output"));

        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of((req, policyCtx) -> DelegationPolicyResult.allow()));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "Write a report");

        assertThat(result).isEqualTo("written output");
    }

    @Test
    void allowPolicy_producesSuccessResponse() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(
                List.of(writer), 3, executionContext, executor, List.of((r, c) -> DelegationPolicyResult.allow()));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "Write");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    // ========================
    // REJECT policy blocks delegation
    // ========================

    @Test
    void rejectPolicy_preventsWorkerExecution() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of((req, policyCtx) -> DelegationPolicyResult.reject("access denied")));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "Write a report");

        verifyNoInteractions(executor);
    }

    @Test
    void rejectPolicy_returnsRejectionMessageToLlm() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of((req, policyCtx) -> DelegationPolicyResult.reject("access denied")));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "Write a report");

        assertThat(result).contains("access denied");
    }

    @Test
    void rejectPolicy_producesFailureResponse() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of((req, policyCtx) -> DelegationPolicyResult.reject("policy violation")));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
    }

    @Test
    void rejectPolicy_failureResponseContainsReason() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of((req, policyCtx) -> DelegationPolicyResult.reject("region not set")));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(tool.getDelegationResponses().get(0).errors()).anyMatch(e -> e.contains("region not set"));
    }

    @Test
    void rejectPolicy_doesNotAccumulateDelegatedOutputs() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of((req, policyCtx) -> DelegationPolicyResult.reject("denied")));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(tool.getDelegatedOutputs()).isEmpty();
    }

    // ========================
    // MODIFY policy replaces the request
    // ========================

    @Test
    void modifyPolicy_replacesRequestForWorkerExecution() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        // Policy modifies the task description
        DelegationContext ctx =
                DelegationContext.create(List.of(writer), 3, executionContext, executor, List.of((req, policyCtx) -> {
                    var enriched = req.toBuilder()
                            .taskDescription("Enriched: " + req.getTaskDescription())
                            .build();
                    return DelegationPolicyResult.modify(enriched);
                }));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "original description");

        // Execution proceeds normally
        assertThat(result).isEqualTo("output");
        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.SUCCESS);
    }

    // ========================
    // Multiple policies evaluated in order
    // ========================

    @Test
    void multiplePolicies_allAllow_workerExecutes() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of(
                        (req, c) -> DelegationPolicyResult.allow(),
                        (req, c) -> DelegationPolicyResult.allow(),
                        (req, c) -> DelegationPolicyResult.allow()));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "task");

        assertThat(result).isEqualTo("output");
    }

    @Test
    void multiplePolicies_firstRejects_shortCircuits() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of(
                        (req, c) -> DelegationPolicyResult.reject("first policy rejects"),
                        (req, c) -> DelegationPolicyResult.allow() // should not be reached
                        ));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "task");

        assertThat(result).contains("first policy rejects");
        verifyNoInteractions(executor);
    }

    @Test
    void multiplePolicies_secondRejects_workerNotInvoked() {
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of(
                        (req, c) -> DelegationPolicyResult.allow(),
                        (req, c) -> DelegationPolicyResult.reject("second policy rejects")));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "task");

        assertThat(result).contains("second policy rejects");
        verifyNoInteractions(executor);
    }

    @Test
    void modifyThenReject_usesModifiedRequestForRejection() {
        // Policy 1 modifies the role; policy 2 rejects based on modified request
        DelegationContext ctx = DelegationContext.create(
                List.of(writer),
                3,
                executionContext,
                executor,
                List.of(
                        (req, c) -> {
                            var modified = req.toBuilder()
                                    .taskDescription("modified task")
                                    .build();
                            return DelegationPolicyResult.modify(modified);
                        },
                        (req, c) -> {
                            // By this point, req should be the modified request
                            if (req.getTaskDescription().startsWith("modified")) {
                                return DelegationPolicyResult.reject("reject after modify");
                            }
                            return DelegationPolicyResult.allow();
                        }));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "original task");

        assertThat(result).contains("reject after modify");
        verifyNoInteractions(executor);
    }

    // ========================
    // Policies propagated through DelegationContext.descend()
    // ========================

    @Test
    void policies_propagatedThroughDescend() {
        DelegationPolicy rejectAll = (req, c) -> DelegationPolicyResult.reject("always reject");

        DelegationContext rootCtx =
                DelegationContext.create(List.of(writer), 3, executionContext, executor, List.of(rejectAll));

        DelegationContext childCtx = rootCtx.descend();

        assertThat(childCtx.getPolicies()).hasSize(1);
        assertThat(childCtx.getPolicies()).containsExactly(rejectAll);
    }

    // ========================
    // No policies: default behavior unchanged
    // ========================

    @Test
    void noPolicies_workerExecutesNormally() {
        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        DelegationContext ctx = DelegationContext.create(List.of(writer), 3, executionContext, executor, List.of());

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "task");

        assertThat(result).isEqualTo("output");
    }

    // ========================
    // MODIFY policy changes agentRole -- re-resolution guards
    // ========================

    @Test
    void modifyPolicy_changesAgentRoleToUnknownAgent_returnsErrorAndFiresFailedEvent() {
        // MODIFY redirects to "NonExistent" which doesn't exist
        DelegationContext ctx =
                DelegationContext.create(List.of(writer), 3, executionContext, executor, List.of((req, c) -> {
                    var modified = req.toBuilder().agentRole("NonExistent").build();
                    return DelegationPolicyResult.modify(modified);
                }));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        String result = tool.delegate("Writer", "task");

        assertThat(result).containsIgnoringCase("NonExistent");
        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        verifyNoInteractions(executor);
    }

    @Test
    void modifyPolicy_changesAgentRoleToSelf_returnsErrorAndFiresFailedEvent() {
        // MODIFY redirects to callerRole -- self-delegation
        DelegationContext ctx =
                DelegationContext.create(List.of(writer, analyst), 3, executionContext, executor, List.of((req, c) -> {
                    var modified = req.toBuilder().agentRole("Researcher").build();
                    return DelegationPolicyResult.modify(modified);
                }));

        // Add "Researcher" as a peer so it resolves but triggers self-delegation guard
        Agent researcher = Agent.builder()
                .role("Researcher")
                .goal("Research things")
                .llm(mock(dev.langchain4j.model.chat.ChatModel.class))
                .build();
        DelegationContext ctxWithSelf = DelegationContext.create(
                List.of(writer, researcher), 3, executionContext, executor, List.of((req, c) -> {
                    var modified = req.toBuilder().agentRole("Researcher").build();
                    return DelegationPolicyResult.modify(modified);
                }));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctxWithSelf);
        String result = tool.delegate("Writer", "task");

        assertThat(result).containsIgnoringCase("self-delegation");
        assertThat(tool.getDelegationResponses()).hasSize(1);
        assertThat(tool.getDelegationResponses().get(0).status()).isEqualTo(DelegationStatus.FAILURE);
        verifyNoInteractions(executor);
    }

    // ========================
    // Policy context contains correct delegation info
    // ========================

    @Test
    void policyContext_containsCorrectCallerRole() {
        String[] capturedRole = {null};

        DelegationContext ctx =
                DelegationContext.create(List.of(writer), 3, executionContext, executor, List.of((req, policyCtx) -> {
                    capturedRole[0] = policyCtx.delegatingAgentRole();
                    return DelegationPolicyResult.allow();
                }));

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(capturedRole[0]).isEqualTo("Researcher");
    }

    @Test
    void policyContext_containsCurrentDepth() {
        int[] capturedDepth = {-1};

        DelegationContext ctx =
                DelegationContext.create(List.of(writer), 3, executionContext, executor, List.of((req, policyCtx) -> {
                    capturedDepth[0] = policyCtx.currentDepth();
                    return DelegationPolicyResult.allow();
                }));

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(capturedDepth[0]).isEqualTo(0); // root depth
    }

    @Test
    void policyContext_containsAvailableWorkerRoles() {
        List<String> capturedRoles[] = new List[] {null};

        DelegationContext ctx = DelegationContext.create(
                List.of(writer, analyst), 3, executionContext, executor, List.of((req, policyCtx) -> {
                    capturedRoles[0] = policyCtx.availableWorkerRoles();
                    return DelegationPolicyResult.allow();
                }));

        when(executor.execute(any(Task.class), any(), any(ExecutionContext.class), any(DelegationContext.class)))
                .thenReturn(makeOutput("output"));

        AgentDelegationTool tool = new AgentDelegationTool("Researcher", ctx);
        tool.delegate("Writer", "task");

        assertThat(capturedRoles[0]).contains("Writer", "Analyst");
    }
}
