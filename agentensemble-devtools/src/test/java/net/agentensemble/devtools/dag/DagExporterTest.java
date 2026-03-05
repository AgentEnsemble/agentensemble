package net.agentensemble.devtools.dag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DagExporter}.
 *
 * <p>Tests cover the core DAG analysis algorithms: topological level computation,
 * parallel group construction, and critical path detection across a variety of
 * graph shapes (linear, fan-out, fan-in, diamond, disconnected, single task).
 */
class DagExporterTest {

    private Agent agentA;
    private Agent agentB;
    private Agent agentC;
    private Agent agentD;

    @BeforeEach
    void setUp() {
        ChatModel stub = new NoOpChatModel();
        agentA = Agent.builder().role("Agent A").goal("goal A").llm(stub).build();
        agentB = Agent.builder().role("Agent B").goal("goal B").llm(stub).build();
        agentC = Agent.builder().role("Agent C").goal("goal C").llm(stub).build();
        agentD = Agent.builder().role("Agent D").goal("goal D").llm(stub).build();
    }

    // ========================
    // Null / empty validation
    // ========================

    @Test
    void build_nullEnsemble_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> DagExporter.build(null));
    }

    @Test
    void build_ensembleWithNoTasks_throwsIllegalArgument() {
        Ensemble ensemble =
                Ensemble.builder().agent(agentA).workflow(Workflow.SEQUENTIAL).build();
        assertThatIllegalArgumentException().isThrownBy(() -> DagExporter.build(ensemble));
    }

    // ========================
    // Single task
    // ========================

    @Test
    void build_singleTask_producesRootWithGroupZero() {
        Task task = task("Research AI", "A report", agentA);
        Ensemble ensemble = ensemble(List.of(agentA), List.of(task), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getTasks()).hasSize(1);
        DagTaskNode node = dag.getTasks().get(0);
        assertThat(node.getId()).isEqualTo("0");
        assertThat(node.getDescription()).isEqualTo("Research AI");
        assertThat(node.getAgentRole()).isEqualTo("Agent A");
        assertThat(node.getDependsOn()).isEmpty();
        assertThat(node.getParallelGroup()).isEqualTo(0);
        assertThat(node.isOnCriticalPath()).isTrue();
    }

    @Test
    void build_singleTask_criticalPathContainsOnlyThatTask() {
        Task task = task("Research AI", "A report", agentA);
        Ensemble ensemble = ensemble(List.of(agentA), List.of(task), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getCriticalPath()).containsExactly("0");
        assertThat(dag.getParallelGroups()).hasSize(1);
        assertThat(dag.getParallelGroups().get(0)).containsExactly("0");
    }

    // ========================
    // Linear chain (A -> B -> C)
    // ========================

    @Test
    void build_linearChain_computesCorrectLevels() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Task taskC = task("Task C", "Output C", agentC, taskB);
        Ensemble ensemble =
                ensemble(List.of(agentA, agentB, agentC), List.of(taskA, taskB, taskC), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(nodeById(dag, "0").getParallelGroup()).isEqualTo(0);
        assertThat(nodeById(dag, "1").getParallelGroup()).isEqualTo(1);
        assertThat(nodeById(dag, "2").getParallelGroup()).isEqualTo(2);
    }

    @Test
    void build_linearChain_criticalPathIsEntireChain() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Task taskC = task("Task C", "Output C", agentC, taskB);
        Ensemble ensemble =
                ensemble(List.of(agentA, agentB, agentC), List.of(taskA, taskB, taskC), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getCriticalPath()).containsExactly("0", "1", "2");
        assertThat(dag.getTasks()).allMatch(DagTaskNode::isOnCriticalPath);
    }

    @Test
    void build_linearChain_dependsOnIsCorrect() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Ensemble ensemble = ensemble(List.of(agentA, agentB), List.of(taskA, taskB), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(nodeById(dag, "0").getDependsOn()).isEmpty();
        assertThat(nodeById(dag, "1").getDependsOn()).containsExactly("0");
    }

    // ========================
    // Fan-out: A -> B and A -> C (two roots fan to one shared dependency)
    // Actually, fan-out: one root fans out to multiple dependents
    // A -> B, A -> C
    // ========================

    @Test
    void build_fanOut_bAndCAtSameLevel() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Task taskC = task("Task C", "Output C", agentC, taskA);
        Ensemble ensemble = ensemble(List.of(agentA, agentB, agentC), List.of(taskA, taskB, taskC), Workflow.PARALLEL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(nodeById(dag, "0").getParallelGroup()).isEqualTo(0);
        assertThat(nodeById(dag, "1").getParallelGroup()).isEqualTo(1);
        assertThat(nodeById(dag, "2").getParallelGroup()).isEqualTo(1);
    }

    @Test
    void build_fanOut_parallelGroupsContainBothDependents() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Task taskC = task("Task C", "Output C", agentC, taskA);
        Ensemble ensemble = ensemble(List.of(agentA, agentB, agentC), List.of(taskA, taskB, taskC), Workflow.PARALLEL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getParallelGroups()).hasSize(2);
        assertThat(dag.getParallelGroups().get(0)).containsExactly("0");
        assertThat(dag.getParallelGroups().get(1)).containsExactlyInAnyOrder("1", "2");
    }

    // ========================
    // Diamond: A -> B, A -> C, B -> D, C -> D
    // ========================

    @Test
    void build_diamond_levelsAreCorrect() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Task taskC = task("Task C", "Output C", agentC, taskA);
        Task taskD = task("Task D", "Output D", agentD, taskB, taskC);
        Ensemble ensemble = ensemble(
                List.of(agentA, agentB, agentC, agentD), List.of(taskA, taskB, taskC, taskD), Workflow.PARALLEL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(nodeById(dag, "0").getParallelGroup()).isEqualTo(0); // A
        assertThat(nodeById(dag, "1").getParallelGroup()).isEqualTo(1); // B
        assertThat(nodeById(dag, "2").getParallelGroup()).isEqualTo(1); // C
        assertThat(nodeById(dag, "3").getParallelGroup()).isEqualTo(2); // D
    }

    @Test
    void build_diamond_criticalPathLengthIsThree() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB, taskA);
        Task taskC = task("Task C", "Output C", agentC, taskA);
        Task taskD = task("Task D", "Output D", agentD, taskB, taskC);
        Ensemble ensemble = ensemble(
                List.of(agentA, agentB, agentC, agentD), List.of(taskA, taskB, taskC, taskD), Workflow.PARALLEL);

        DagModel dag = DagExporter.build(ensemble);

        // Critical path must start at A (id=0), end at D (id=3), length 3
        assertThat(dag.getCriticalPath()).hasSize(3);
        assertThat(dag.getCriticalPath().get(0)).isEqualTo("0"); // A is always root
        assertThat(dag.getCriticalPath().get(2)).isEqualTo("3"); // D is always leaf
    }

    // ========================
    // Two independent roots (no shared dependencies)
    // A, B (both roots, no edges)
    // ========================

    @Test
    void build_twoIndependentRoots_bothAtLevelZero() {
        Task taskA = task("Task A", "Output A", agentA);
        Task taskB = task("Task B", "Output B", agentB);
        Ensemble ensemble = ensemble(List.of(agentA, agentB), List.of(taskA, taskB), Workflow.PARALLEL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(nodeById(dag, "0").getParallelGroup()).isEqualTo(0);
        assertThat(nodeById(dag, "1").getParallelGroup()).isEqualTo(0);
        assertThat(dag.getParallelGroups()).hasSize(1);
        assertThat(dag.getParallelGroups().get(0)).containsExactlyInAnyOrder("0", "1");
    }

    // ========================
    // Workflow name is preserved
    // ========================

    @Test
    void build_preservesWorkflowName() {
        Task task = task("Task A", "Output A", agentA);
        Ensemble ensemble = ensemble(List.of(agentA), List.of(task), Workflow.PARALLEL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getWorkflow()).isEqualTo("PARALLEL");
    }

    // ========================
    // Agent nodes are included
    // ========================

    @Test
    void build_agentNodesIncludeAllAgents() {
        Task task = task("Task A", "Output A", agentA);
        Ensemble ensemble = ensemble(List.of(agentA, agentB), List.of(task), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getAgents()).hasSize(2);
        assertThat(dag.getAgents()).extracting(DagAgentNode::getRole).containsExactly("Agent A", "Agent B");
    }

    @Test
    void build_agentNodePreservesGoalAndDelegation() {
        Task task = task("Task A", "Output A", agentA);
        Agent delegatingAgent = Agent.builder()
                .role("Delegator")
                .goal("Coordinate work")
                .llm(new NoOpChatModel())
                .allowDelegation(true)
                .build();
        Ensemble ensemble = ensemble(List.of(delegatingAgent), List.of(task), Workflow.HIERARCHICAL);

        DagModel dag = DagExporter.build(ensemble);

        DagAgentNode node = dag.getAgents().get(0);
        assertThat(node.getGoal()).isEqualTo("Coordinate work");
        assertThat(node.isAllowDelegation()).isTrue();
    }

    // ========================
    // Schema version and type
    // ========================

    @Test
    void build_schemaVersionAndTypeAreSet() {
        Task task = task("Task A", "Output A", agentA);
        Ensemble ensemble = ensemble(List.of(agentA), List.of(task), Workflow.SEQUENTIAL);

        DagModel dag = DagExporter.build(ensemble);

        assertThat(dag.getSchemaVersion()).isEqualTo(DagModel.CURRENT_SCHEMA_VERSION);
        assertThat(dag.getType()).isEqualTo("dag");
    }

    // ========================
    // JSON round-trip
    // ========================

    @Test
    void build_toJson_producesValidJsonWithExpectedFields() {
        Task taskA = task("Research AI", "A report", agentA);
        Task taskB = task("Write article", "An article", agentB, taskA);
        Ensemble ensemble = ensemble(List.of(agentA, agentB), List.of(taskA, taskB), Workflow.SEQUENTIAL);

        String json = DagExporter.build(ensemble).toJson();

        assertThat(json).contains("\"schemaVersion\"");
        assertThat(json).contains("\"type\" : \"dag\"");
        assertThat(json).contains("\"workflow\" : \"SEQUENTIAL\"");
        assertThat(json).contains("\"agents\"");
        assertThat(json).contains("\"tasks\"");
        assertThat(json).contains("\"parallelGroups\"");
        assertThat(json).contains("\"criticalPath\"");
        assertThat(json).contains("\"Research AI\"");
        assertThat(json).contains("\"Agent A\"");
    }

    // ========================
    // Helpers
    // ========================

    private static Task task(String description, String expectedOutput, Agent agent, Task... context) {
        return Task.builder()
                .description(description)
                .expectedOutput(expectedOutput)
                .agent(agent)
                .context(List.of(context))
                .build();
    }

    private static Ensemble ensemble(List<Agent> agents, List<Task> tasks, Workflow workflow) {
        Ensemble.EnsembleBuilder builder = Ensemble.builder().workflow(workflow);
        for (Agent a : agents) {
            builder.agent(a);
        }
        for (Task t : tasks) {
            builder.task(t);
        }
        return builder.build();
    }

    private static DagTaskNode nodeById(DagModel dag, String id) {
        return dag.getTasks().stream()
                .filter(n -> id.equals(n.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No task node with id=" + id));
    }

    /**
     * Minimal no-op ChatModel stub for test agent construction.
     * The devtools module never calls the LLM; this stub satisfies Agent validation.
     */
    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called in devtools tests");
        }
    }
}
