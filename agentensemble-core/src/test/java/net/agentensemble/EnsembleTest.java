package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

class EnsembleTest {

    private Agent agent(String role) {
        return Agent.builder()
                .role(role)
                .goal("Do the work")
                .llm(mock(ChatModel.class))
                .build();
    }

    private Task task(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("Expected result")
                .agent(agent)
                .build();
    }

    // ========================
    // Validation: tasks
    // ========================

    @Test
    void testRun_withEmptyTasks_throwsValidation() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().agent(researcher).build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("task");
    }

    // ========================
    // Validation: agents
    // ========================

    @Test
    void testRun_withEmptyAgents_throwsValidation() {
        var researcher = agent("Researcher");
        var researchTask = task("Research task", researcher);
        var ensemble = Ensemble.builder().task(researchTask).build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("agent");
    }

    // ========================
    // Validation: agent membership
    // ========================

    @Test
    void testRun_withUnregisteredAgent_throwsValidation() {
        var registeredAgent = agent("Researcher");
        var unregisteredAgent = agent("Writer");
        var researchTask = task("Research task", registeredAgent);
        var writeTask = task("Write task", unregisteredAgent); // unregistered agent

        var ensemble = Ensemble.builder()
                .agent(registeredAgent) // only researcher registered
                .task(researchTask)
                .task(writeTask)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Writer")
                .hasMessageContaining("not in the ensemble");
    }

    // ========================
    // Validation: context ordering (forward reference)
    // ========================

    @Test
    void testRun_withForwardContextReference_throwsValidation() {
        // True A->B->A cycles cannot be constructed with immutable Task objects.
        // This test verifies that a forward reference (taskA depends on taskB, but
        // taskA appears before taskB in the list) is caught by validateContextOrdering.
        var researcher = agent("Researcher");
        var taskA = task("Task A", researcher);
        var taskB =
                taskA.toBuilder().description("Task B").context(List.of(taskA)).build();
        var taskAWithDep = taskA.toBuilder().context(List.of(taskB)).build();

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .task(taskAWithDep)
                .task(taskB)
                .build();

        // taskAWithDep references taskB, but taskB appears later in the list
        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("context");
    }

    // ========================
    // Validation: context ordering
    // ========================

    @Test
    void testRun_withContextOrderViolation_throwsValidation() {
        var researcher = agent("Researcher");
        var firstTask = task("First task", researcher);
        // Second task has context pointing to first, but they're added in wrong order
        var secondTask = Task.builder()
                .description("Second task")
                .expectedOutput("Output")
                .agent(researcher)
                .context(List.of(firstTask))
                .build();

        // Adding secondTask BEFORE firstTask in the list violates ordering
        var ensemble = Ensemble.builder()
                .agent(researcher)
                .task(secondTask) // second task first -- violation
                .task(firstTask)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("context")
                .hasMessageContaining("later");
    }

    // ========================
    // Validation: defaults
    // ========================

    @Test
    void testDefaultWorkflow_isSequential() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().agent(researcher).build();

        assertThat(ensemble.getWorkflow()).isEqualTo(Workflow.SEQUENTIAL);
    }

    @Test
    void testDefaultVerbose_isFalse() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().agent(researcher).build();

        assertThat(ensemble.isVerbose()).isFalse();
    }

    // ========================
    // Validation: valid config still stubs execution
    // ========================

    @Test
    void testRun_withValidConfig_completesValidationBeforeExecution() {
        var researcher = agent("Researcher");
        var researchTask = task("Research AI trends", researcher);

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .task(researchTask)
                .workflow(Workflow.SEQUENTIAL)
                .verbose(true)
                .build();

        // Validation passes; execution is stubbed in Issue #12
        // No ValidationException thrown means validation succeeded
        assertThatThrownBy(ensemble::run).isNotInstanceOf(ValidationException.class);
    }

    // ========================
    // Validation: hierarchical roles
    // ========================

    @Test
    void testRun_hierarchical_withReservedManagerRole_throwsValidation() {
        var manager = agent("Manager"); // reserved role
        var worker = agent("Worker");
        var taskA = task("Task A", manager);
        var taskB = task("Task B", worker);

        var ensemble = Ensemble.builder()
                .agent(manager)
                .agent(worker)
                .task(taskA)
                .task(taskB)
                .workflow(Workflow.HIERARCHICAL)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Manager")
                .hasMessageContaining("reserved");
    }

    @Test
    void testRun_hierarchical_withDuplicateRoles_throwsValidation() {
        var researcher1 = agent("Researcher");
        var researcher2 = agent("Researcher"); // duplicate role
        var taskA = task("Task A", researcher1);
        var taskB = task("Task B", researcher2);

        var ensemble = Ensemble.builder()
                .agent(researcher1)
                .agent(researcher2)
                .task(taskA)
                .task(taskB)
                .workflow(Workflow.HIERARCHICAL)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("Researcher");
    }

    @Test
    void testRun_hierarchical_withZeroManagerMaxIterations_throwsValidation() {
        var worker = agent("Worker");
        var taskA = task("Task A", worker);

        var ensemble = Ensemble.builder()
                .agent(worker)
                .task(taskA)
                .workflow(Workflow.HIERARCHICAL)
                .managerMaxIterations(0)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("managerMaxIterations");
    }

    @Test
    void testRun_contextTask_notInEnsemble_givesHelpfulMessage() {
        var researcher = agent("Researcher");
        var externalTask = task("External task not in ensemble", researcher);
        var mainTask = Task.builder()
                .description("Main task")
                .expectedOutput("Output")
                .agent(researcher)
                .context(List.of(externalTask))
                .build();

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .task(mainTask) // externalTask not added to ensemble
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not in the ensemble");
    }

    // ========================
    // Listener builder
    // ========================

    @Test
    void testDefaultListeners_isEmpty() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().agent(researcher).build();
        assertThat(ensemble.getListeners()).isEmpty();
    }

    @Test
    void testListener_addsToList() {
        var researcher = agent("Researcher");
        EnsembleListener listener = new EnsembleListener() {};
        var ensemble = Ensemble.builder().agent(researcher).listener(listener).build();
        assertThat(ensemble.getListeners()).containsExactly(listener);
    }

    @Test
    void testOnTaskStart_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().agent(researcher).onTaskStart(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testOnTaskComplete_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble =
                Ensemble.builder().agent(researcher).onTaskComplete(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testOnTaskFailed_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble =
                Ensemble.builder().agent(researcher).onTaskFailed(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testOnToolCall_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().agent(researcher).onToolCall(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testMultipleListeners_allAccumulate() {
        var researcher = agent("Researcher");
        EnsembleListener l1 = new EnsembleListener() {};
        EnsembleListener l2 = new EnsembleListener() {};
        var ensemble = Ensemble.builder()
                .agent(researcher)
                .listener(l1)
                .listener(l2)
                .onTaskStart(e -> {})
                .build();
        assertThat(ensemble.getListeners()).hasSize(3);
    }

    // ========================
    // Multi-task scenarios
    // ========================

    @Test
    void testRun_withTwoTasksInOrder_passesValidation() {
        var researcher = agent("Researcher");
        var writer = agent("Writer");
        var researchTask = task("Research task", researcher);
        var writeTask = Task.builder()
                .description("Write task")
                .expectedOutput("Article")
                .agent(writer)
                .context(List.of(researchTask))
                .build();

        var ensemble = Ensemble.builder()
                .agent(researcher)
                .agent(writer)
                .task(researchTask) // research first
                .task(writeTask) // write second (depends on research)
                .build();

        // No ValidationException -- ordering is correct
        assertThatThrownBy(ensemble::run).isNotInstanceOf(ValidationException.class);
    }
}
