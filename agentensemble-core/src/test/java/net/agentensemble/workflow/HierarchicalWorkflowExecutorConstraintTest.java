package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.exception.ConstraintViolationException;
import net.agentensemble.execution.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HierarchicalWorkflowExecutorConstraintTest {

    private ChatModel managerModel;
    private ChatModel researcherModel;
    private ChatModel analystModel;
    private Agent researcher;
    private Agent analyst;
    private Task task;

    @BeforeEach
    void setUp() {
        managerModel = mock(ChatModel.class);
        researcherModel = mock(ChatModel.class);
        analystModel = mock(ChatModel.class);

        researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(researcherModel)
                .build();
        analyst = Agent.builder()
                .role("Analyst")
                .goal("Analyse data")
                .llm(analystModel)
                .build();

        task = Task.builder()
                .description("Research and analyse AI trends")
                .expectedOutput("AI trends analysis")
                .agent(researcher)
                .build();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("delegate-1")
                .name("delegateTask")
                .arguments(
                        "{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
    }

    private HierarchicalWorkflowExecutor executorWithConstraints(HierarchicalConstraints constraints) {
        return new HierarchicalWorkflowExecutor(
                managerModel,
                List.of(researcher, analyst),
                20,
                3,
                DefaultManagerPromptStrategy.DEFAULT,
                List.of(),
                constraints);
    }

    // ========================
    // Backward compatibility: no constraints
    // ========================

    @Test
    void noConstraints_executesNormally() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Final synthesis"));

        var executor = new HierarchicalWorkflowExecutor(managerModel, List.of(researcher), 20, 3);

        var output = executor.execute(List.of(task), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Final synthesis");
    }

    // ========================
    // Required workers enforcement
    // ========================

    @Test
    void requiredWorker_workerIsDelegatedTo_executesNormally() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Final synthesis"));

        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Researcher").build();

        var output = executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Final synthesis");
    }

    @Test
    void requiredWorker_notCalled_throwsConstraintViolationException() {
        // Manager answers directly without delegating
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct manager answer"));

        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Researcher").build();

        assertThatThrownBy(
                        () -> executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled()))
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    var ce = (ConstraintViolationException) e;
                    assertThat(ce.getViolations()).hasSize(1);
                    assertThat(ce.getViolations().get(0)).contains("Researcher");
                });
    }

    @Test
    void requiredWorker_notCalled_exceptionCarriesEmptyCompletedOutputs() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct answer"));

        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Analyst").build();

        assertThatThrownBy(
                        () -> executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled()))
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    // No workers were called, so completed outputs are empty
                    assertThat(((ConstraintViolationException) e).getCompletedTaskOutputs())
                            .isEmpty();
                });
    }

    @Test
    void requiredWorker_notCalled_exceptionCarriesCompletedOutputsFromOtherWorkers() {
        // Researcher is called but Analyst (the required worker) is not
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Final answer without Analyst"));

        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Analyst").build();

        assertThatThrownBy(
                        () -> executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled()))
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    var ce = (ConstraintViolationException) e;
                    // Researcher's output is in completed outputs
                    assertThat(ce.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(ce.getCompletedTaskOutputs().get(0).getAgentRole())
                            .isEqualTo("Researcher");
                });
    }

    // ========================
    // allowedWorkers enforcement
    // ========================

    @Test
    void allowedWorkers_managerDelegatesToDisallowedWorker_receivesRejectionAndSynthesizes() {
        // Manager tries to delegate to Analyst (not in allowed set), gets rejection, then synthesizes
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Analyst", "Analyse data"))
                .thenReturn(textResponse("Synthesis after rejection"));

        var constraints = HierarchicalConstraints.builder()
                .allowedWorker("Researcher") // Only Researcher allowed, not Analyst
                .build();

        // The rejection is returned as an error message to the LLM (not an exception).
        // The manager then synthesizes without Analyst.
        var output = executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Synthesis after rejection");
    }

    // ========================
    // Per-worker cap enforcement
    // ========================

    @Test
    void maxCallsPerWorker_managerExceedsCap_secondDelegationRejected() {
        // Manager delegates to Researcher twice; second call is rejected by enforcer
        // Manager then synthesizes
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research output"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "First task"))
                .thenReturn(delegateCallResponse("Researcher", "Second task"))
                .thenReturn(textResponse("Synthesis after cap"));

        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Researcher", 1) // cap of 1
                .build();

        // Second delegation to Researcher is rejected as a policy (error returned to manager LLM)
        // not as an exception -- the manager continues and synthesizes
        var output = executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Synthesis after cap");
        // Only one delegation was actually executed (the second was blocked)
        assertThat(output.getTaskOutputs()).hasSize(2); // researcher + manager
    }

    // ========================
    // Global cap enforcement
    // ========================

    @Test
    void globalMaxDelegations_reachedBeforeManagerDone_subsequentDelegationsRejected() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research output"));
        when(analystModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Analyst output"));
        // Global cap of 1: Researcher is called, Analyst delegation is rejected, manager synthesizes
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research"))
                .thenReturn(delegateCallResponse("Analyst", "Analyse"))
                .thenReturn(textResponse("Synthesis with cap"));

        var constraints =
                HierarchicalConstraints.builder().globalMaxDelegations(1).build();

        var output = executorWithConstraints(constraints).execute(List.of(task), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Synthesis with cap");
        // Only one delegation was executed; Analyst was blocked
        assertThat(output.getTaskOutputs()).hasSize(2); // only researcher + manager
    }

    // ========================
    // Constraints + existing delegation policies coexist
    // ========================

    @Test
    void constraintsAndUserPolicies_bothApplied() {
        // User policy rejects "Analyst", constraint requires "Researcher" to be called
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Final synthesis with policies"));

        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Researcher").build();

        // User policy: reject delegations to "Analyst"
        var userPolicy = (net.agentensemble.delegation.policy.DelegationPolicy) (req, ctx) -> {
            if ("Analyst".equals(req.getAgentRole())) {
                return net.agentensemble.delegation.policy.DelegationPolicyResult.reject(
                        "Analyst blocked by user policy");
            }
            return net.agentensemble.delegation.policy.DelegationPolicyResult.allow();
        };

        var executor = new HierarchicalWorkflowExecutor(
                managerModel,
                List.of(researcher, analyst),
                20,
                3,
                DefaultManagerPromptStrategy.DEFAULT,
                List.of(userPolicy),
                constraints);

        var output = executor.execute(List.of(task), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Final synthesis with policies");
    }
}
