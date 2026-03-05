package net.agentensemble.devtools.dag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.mapreduce.MapReduceEnsemble;
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
        assertThatIllegalArgumentException().isThrownBy(() -> DagExporter.build((Ensemble) null));
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
    // MapReduceEnsemble overload
    // ========================

    @Test
    void build_nullMapReduceEnsemble_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> DagExporter.build((MapReduceEnsemble<?>) null));
    }

    @Test
    void build_mapReduceEnsemble_setsMapReduceModeToStatic() {
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B", "C"), 5);
        DagModel dag = DagExporter.build(mre);
        assertThat(dag.getMapReduceMode()).isEqualTo("STATIC");
    }

    @Test
    void build_standardEnsemble_mapReduceModeIsNull() {
        Task task = task("Task A", "Output A", agentA);
        Ensemble ensemble = ensemble(List.of(agentA), List.of(task), Workflow.SEQUENTIAL);
        DagModel dag = DagExporter.build(ensemble);
        assertThat(dag.getMapReduceMode()).isNull();
    }

    @Test
    void build_mapReduceEnsemble_mapTaskNodesHaveCorrectNodeType() {
        // N=3, K=5: 3 map + 1 final = 4 tasks
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B", "C"), 5);
        DagModel dag = DagExporter.build(mre);

        assertThat(dag.getTasks()).hasSize(4);
        // First 3 tasks are map tasks
        for (int i = 0; i < 3; i++) {
            assertThat(dag.getTasks().get(i).getNodeType()).isEqualTo("map");
        }
    }

    @Test
    void build_mapReduceEnsemble_finalReduceNodeHasCorrectNodeType() {
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B", "C"), 5);
        DagModel dag = DagExporter.build(mre);

        DagTaskNode finalNode = dag.getTasks().get(3);
        assertThat(finalNode.getNodeType()).isEqualTo("final-reduce");
    }

    @Test
    void build_mapReduceEnsemble_intermediateReduceNodesHaveCorrectNodeType() {
        // N=4, K=3: 4 map + 2 L1 + 1 final = 7 tasks
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B", "C", "D"), 3);
        DagModel dag = DagExporter.build(mre);

        assertThat(dag.getTasks()).hasSize(7);
        // tasks[4] and tasks[5] are intermediate reduce nodes
        assertThat(dag.getTasks().get(4).getNodeType()).isEqualTo("reduce");
        assertThat(dag.getTasks().get(5).getNodeType()).isEqualTo("reduce");
        // tasks[6] is the final reduce
        assertThat(dag.getTasks().get(6).getNodeType()).isEqualTo("final-reduce");
    }

    @Test
    void build_mapReduceEnsemble_mapReduceLevelsAreCorrect() {
        // N=4, K=3: map=0, L1-reduce=1, final=2
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B", "C", "D"), 3);
        DagModel dag = DagExporter.build(mre);

        // Map tasks at level 0
        for (int i = 0; i < 4; i++) {
            assertThat(dag.getTasks().get(i).getMapReduceLevel()).isEqualTo(0);
        }
        // L1 reduce at level 1
        assertThat(dag.getTasks().get(4).getMapReduceLevel()).isEqualTo(1);
        assertThat(dag.getTasks().get(5).getMapReduceLevel()).isEqualTo(1);
        // Final at level 2
        assertThat(dag.getTasks().get(6).getMapReduceLevel()).isEqualTo(2);
    }

    @Test
    void build_mapReduceEnsemble_standardFieldsAreUnaffected() {
        // Verify that the basic DagModel fields (workflow, agents, criticalPath, etc.)
        // are still populated correctly when using the MapReduceEnsemble overload.
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B"), 5);
        DagModel dag = DagExporter.build(mre);

        assertThat(dag.getWorkflow()).isEqualTo("PARALLEL");
        assertThat(dag.getType()).isEqualTo("dag");
        assertThat(dag.getSchemaVersion()).isEqualTo(DagModel.CURRENT_SCHEMA_VERSION);
        assertThat(dag.getAgents()).hasSize(3); // 2 map + 1 final reduce
        assertThat(dag.getTasks()).hasSize(3);
        assertThat(dag.getCriticalPath()).isNotEmpty();
        assertThat(dag.getParallelGroups()).isNotEmpty();
    }

    @Test
    void build_mapReduceEnsemble_toJson_includesMapReduceFields() {
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A", "B", "C"), 5);
        String json = DagExporter.build(mre).toJson();

        assertThat(json).contains("\"mapReduceMode\" : \"STATIC\"");
        assertThat(json).contains("\"nodeType\" : \"map\"");
        assertThat(json).contains("\"nodeType\" : \"final-reduce\"");
        assertThat(json).contains("\"mapReduceLevel\" : 0");
        assertThat(json).contains("\"mapReduceLevel\" : 1");
    }

    @Test
    void build_mapReduceEnsemble_schemaVersionIs_1_1() {
        MapReduceEnsemble<String> mre = buildTestMapReduceEnsemble(List.of("A"), 3);
        DagModel dag = DagExporter.build(mre);
        assertThat(dag.getSchemaVersion()).isEqualTo("1.1");
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

    private MapReduceEnsemble<String> buildTestMapReduceEnsemble(List<String> items, int chunkSize) {
        NoOpChatModel stub = new NoOpChatModel();
        AtomicInteger counter = new AtomicInteger(0);
        return MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> Agent.builder()
                        .role("Map-" + item)
                        .goal("Map " + item)
                        .llm(stub)
                        .build())
                .mapTask((item, agent) -> Task.builder()
                        .description("map " + item)
                        .expectedOutput("mapped " + item)
                        .agent(agent)
                        .build())
                .reduceAgent(() -> Agent.builder()
                        .role("Reducer-" + counter.incrementAndGet())
                        .goal("Reduce")
                        .llm(stub)
                        .build())
                .reduceTask((agent, chunk) -> Task.builder()
                        .description("reduce-" + counter.get())
                        .expectedOutput("reduced")
                        .agent(agent)
                        .context(chunk)
                        .build())
                .chunkSize(chunkSize)
                .build();
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
