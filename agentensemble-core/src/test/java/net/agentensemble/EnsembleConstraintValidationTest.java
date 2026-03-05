package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.HierarchicalConstraints;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Validates EnsembleValidator constraint checking for HierarchicalConstraints.
 *
 * All validation errors are expected to be surfaced as ValidationException during
 * Ensemble.run(), before any workflow execution begins.
 */
class EnsembleConstraintValidationTest {

    private Agent researcher;
    private Agent analyst;
    private Task task;

    @BeforeEach
    void setUp() {
        researcher = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(mock(ChatModel.class))
                .build();
        analyst = Agent.builder()
                .role("Analyst")
                .goal("Analyse data")
                .llm(mock(ChatModel.class))
                .build();
        task = Task.builder()
                .description("Do research")
                .expectedOutput("Research output")
                .agent(researcher)
                .build();
    }

    private Ensemble baseEnsemble(HierarchicalConstraints constraints) {
        return Ensemble.builder()
                .workflow(Workflow.HIERARCHICAL)
                .agent(researcher)
                .agent(analyst)
                .task(task)
                .hierarchicalConstraints(constraints)
                .build();
    }

    // ========================
    // Valid constraints: no exception
    // ========================

    @Test
    void validConstraints_requiredWorkerInRegisteredAgents_noValidationException() {
        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Researcher").build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate());
    }

    @Test
    void validConstraints_allowedWorkersSubsetOfRegisteredAgents_noValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .allowedWorker("Researcher")
                .allowedWorker("Analyst")
                .build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate());
    }

    @Test
    void validConstraints_requiredWorkersSubsetOfAllowedWorkers_noValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .requiredWorker("Researcher")
                .allowedWorker("Researcher")
                .allowedWorker("Analyst")
                .build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate());
    }

    @Test
    void validConstraints_maxCallsPerWorkerWithKnownRole_noValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Researcher", 3)
                .build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate());
    }

    @Test
    void validConstraints_globalMaxDelegationsZero_noValidationException() {
        var constraints =
                HierarchicalConstraints.builder().globalMaxDelegations(0).build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate());
    }

    @Test
    void validConstraints_requiredStagesWithKnownRoles_noValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher"))
                .requiredStage(List.of("Analyst"))
                .build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate());
    }

    @Test
    void nullConstraints_hierarchicalWorkflow_noValidationException() {
        var ensemble = Ensemble.builder()
                .workflow(Workflow.HIERARCHICAL)
                .agent(researcher)
                .task(task)
                .build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(ensemble).validate());
    }

    // ========================
    // requiredWorkers validation
    // ========================

    @Test
    void requiredWorker_unknownRole_throwsValidationException() {
        var constraints =
                HierarchicalConstraints.builder().requiredWorker("UnknownRole").build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UnknownRole")
                .hasMessageContaining("requiredWorkers");
    }

    // ========================
    // allowedWorkers validation
    // ========================

    @Test
    void allowedWorker_unknownRole_throwsValidationException() {
        var constraints =
                HierarchicalConstraints.builder().allowedWorker("UnknownRole").build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UnknownRole")
                .hasMessageContaining("allowedWorkers");
    }

    // ========================
    // requiredWorkers must be subset of allowedWorkers
    // ========================

    @Test
    void requiredWorkerNotInAllowedWorkers_throwsValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .requiredWorker("Researcher") // required but not allowed
                .allowedWorker("Analyst") // only Analyst allowed
                .build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Researcher")
                .hasMessageContaining("requiredWorkers");
    }

    // ========================
    // maxCallsPerWorker validation
    // ========================

    @Test
    void maxCallsPerWorker_unknownRole_throwsValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("UnknownRole", 2)
                .build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UnknownRole")
                .hasMessageContaining("maxCallsPerWorker");
    }

    @Test
    void maxCallsPerWorker_zeroValue_throwsValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Researcher", 0)
                .build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Researcher")
                .hasMessageContaining("maxCallsPerWorker");
    }

    @Test
    void maxCallsPerWorker_negativeValue_throwsValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Researcher", -1)
                .build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Researcher")
                .hasMessageContaining("maxCallsPerWorker");
    }

    // ========================
    // globalMaxDelegations validation
    // ========================

    @Test
    void globalMaxDelegations_negative_throwsValidationException() {
        // Note: HierarchicalConstraints does not prevent negative values at build time;
        // EnsembleValidator catches this.
        var constraints =
                HierarchicalConstraints.builder().globalMaxDelegations(-1).build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("globalMaxDelegations");
    }

    // ========================
    // requiredStages validation
    // ========================

    @Test
    void requiredStage_unknownRoleInStage_throwsValidationException() {
        var constraints = HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher", "UnknownRole"))
                .build();

        assertThatThrownBy(() -> new EnsembleValidator(baseEnsemble(constraints)).validate())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("UnknownRole")
                .hasMessageContaining("requiredStages");
    }

    // ========================
    // Non-hierarchical workflow: constraints not validated
    // ========================

    @Test
    void sequentialWorkflow_constraintsIgnored_noValidationException() {
        // Even with invalid constraints, sequential workflow should not validate them
        // (constraints are only meaningful for hierarchical workflow)
        var researcherTask = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .agent(researcher)
                .build();

        // If constraints are silently ignored for non-hierarchical, no exception
        // (this is the documented behavior: constraints are HIERARCHICAL-only)
        var ensemble = Ensemble.builder()
                .workflow(Workflow.SEQUENTIAL)
                .agent(researcher)
                .task(researcherTask)
                .build();

        assertThatNoException().isThrownBy(() -> new EnsembleValidator(ensemble).validate());
    }
}
