package net.agentensemble.integration;

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
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.ConstraintViolationException;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.HierarchicalConstraints;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for HierarchicalConstraints wired through Ensemble.builder().
 *
 * These tests verify the full constraint lifecycle: EnsembleValidator at build/run time,
 * HierarchicalConstraintEnforcer pre-delegation enforcement, and post-execution validation.
 */
class HierarchicalConstraintsIntegrationTest {

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
                .description("Research and analyse")
                .expectedOutput("Research and analysis output")
                .agent(researcher)
                .build();
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String description) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("delegate-1")
                .name("delegateTask")
                .arguments("{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + description + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
    }

    private Ensemble.EnsembleBuilder baseEnsemble() {
        return Ensemble.builder()
                .workflow(Workflow.HIERARCHICAL)
                .managerLlm(managerModel)
                .agent(researcher)
                .agent(analyst)
                .task(task);
    }

    // ========================
    // Validation at run time
    // ========================

    @Test
    void ensembleRun_requiredWorkerUnknown_throwsValidationException() {
        var ensemble = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .requiredWorker("UnknownRole")
                        .build())
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UnknownRole")
                .hasMessageContaining("requiredWorkers");
    }

    @Test
    void ensembleRun_allowedWorkerUnknown_throwsValidationException() {
        var ensemble = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .allowedWorker("UnknownRole")
                        .build())
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UnknownRole")
                .hasMessageContaining("allowedWorkers");
    }

    @Test
    void ensembleRun_requiredWorkerNotInAllowedWorkers_throwsValidationException() {
        var ensemble = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .requiredWorker("Researcher")
                        .allowedWorker("Analyst")
                        .build())
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Researcher")
                .hasMessageContaining("requiredWorkers");
    }

    // ========================
    // Required workers: happy path
    // ========================

    @Test
    void ensembleRun_requiredWorkerIsDelegatedTo_returnsOutput() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Final synthesis"));

        var output = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .requiredWorker("Researcher")
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Final synthesis");
    }

    // ========================
    // Required workers: violation
    // ========================

    @Test
    void ensembleRun_requiredWorkerNotDelegatedTo_throwsConstraintViolationException() {
        // Manager answers directly without delegating
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct answer"));

        assertThatThrownBy(() -> baseEnsemble()
                        .hierarchicalConstraints(HierarchicalConstraints.builder()
                                .requiredWorker("Researcher")
                                .build())
                        .build()
                        .run())
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    var ce = (ConstraintViolationException) e;
                    assertThat(ce.getViolations()).hasSize(1);
                    assertThat(ce.getViolations().get(0)).contains("Researcher");
                    assertThat(ce.getCompletedTaskOutputs()).isEmpty();
                });
    }

    @Test
    void ensembleRun_requiredWorkerMissing_exceptionCarriesOtherWorkersOutput() {
        // Researcher called, but Analyst (required) is not
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI"))
                .thenReturn(textResponse("Answer without Analyst"));

        assertThatThrownBy(() -> baseEnsemble()
                        .hierarchicalConstraints(HierarchicalConstraints.builder()
                                .requiredWorker("Analyst")
                                .build())
                        .build()
                        .run())
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    var ce = (ConstraintViolationException) e;
                    assertThat(ce.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(ce.getCompletedTaskOutputs().get(0).getAgentRole())
                            .isEqualTo("Researcher");
                });
    }

    // ========================
    // Allowed workers: violation returns error to LLM
    // ========================

    @Test
    void ensembleRun_delegationToDisallowedWorker_managerReceivesErrorAndSynthesizes() {
        // Manager tries Analyst (not allowed), gets rejection, then synthesizes
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Analyst", "Analyse data"))
                .thenReturn(textResponse("Synthesis after rejection"));

        var output = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .allowedWorker("Researcher") // only Researcher allowed
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Synthesis after rejection");
        // No workers actually completed, so only manager output in taskOutputs
        assertThat(output.getTaskOutputs()).hasSize(1);
    }

    // ========================
    // Per-worker cap
    // ========================

    @Test
    void ensembleRun_perWorkerCapExceeded_secondDelegationBlockedManagerSynthesizes() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research output"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Task 1"))
                .thenReturn(delegateCallResponse("Researcher", "Task 2")) // cap = 1
                .thenReturn(textResponse("Synthesis with cap"));

        var output = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .maxCallsPerWorker("Researcher", 1)
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Synthesis with cap");
        // Only first Researcher call completed + manager
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Global max delegations
    // ========================

    @Test
    void ensembleRun_globalCapReached_furtherDelegationsBlockedManagerSynthesizes() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research output"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research"))
                .thenReturn(delegateCallResponse("Analyst", "Analyse")) // global cap = 1
                .thenReturn(textResponse("Synthesis with global cap"));

        var output = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .globalMaxDelegations(1)
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Synthesis with global cap");
        // Only Researcher completed, Analyst was blocked
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // Stage ordering
    // ========================

    @Test
    void ensembleRun_stageOrderingRespected_bothWorkersCompleteInOrder() {
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(analystModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Analysis done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research first"))
                .thenReturn(delegateCallResponse("Analyst", "Analyse second"))
                .thenReturn(textResponse("Final output"));

        var output = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .requiredStage(List.of("Researcher"))
                        .requiredStage(List.of("Analyst"))
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Final output");
        assertThat(output.getTaskOutputs()).hasSize(3); // researcher + analyst + manager
    }

    @Test
    void ensembleRun_stageViolated_analystCalledBeforeResearcher_analystBlocked() {
        // Manager tries Analyst before Researcher (stage 1 before stage 0 complete)
        when(researcherModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Research done"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Analyst", "Analyse first")) // blocked: stage 0 not done
                .thenReturn(delegateCallResponse("Researcher", "Research second")) // stage 0
                .thenReturn(delegateCallResponse("Analyst", "Analyse after researcher")) // now allowed
                .thenReturn(textResponse("Final output"));

        when(analystModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Analysis done"));

        var output = baseEnsemble()
                .hierarchicalConstraints(HierarchicalConstraints.builder()
                        .requiredStage(List.of("Researcher"))
                        .requiredStage(List.of("Analyst"))
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Final output");
        // First Analyst attempt was blocked, then Researcher completed, then Analyst allowed
        // Total: Researcher + Analyst (second attempt) + Manager
        assertThat(output.getTaskOutputs()).hasSize(3);
    }
}
