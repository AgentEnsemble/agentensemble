package net.agentensemble.devtools.dag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.trace.AgentSummary;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.MapReduceLevelSummary;
import net.agentensemble.trace.TaskTrace;
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
    // ExecutionTrace overload (post-execution adaptive DAG export)
    // ========================

    @Test
    void build_nullTrace_throwsIllegalArgument() {
        assertThatIllegalArgumentException().isThrownBy(() -> DagExporter.build((ExecutionTrace) null));
    }

    @Test
    void build_traceWithNoTaskTraces_throwsIllegalArgument() {
        ExecutionTrace emptyTrace = ExecutionTrace.builder()
                .ensembleId("test-id")
                .workflow("MAP_REDUCE_ADAPTIVE")
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .totalDuration(Duration.ZERO)
                .metrics(ExecutionMetrics.EMPTY)
                .build();
        assertThatIllegalArgumentException().isThrownBy(() -> DagExporter.build(emptyTrace));
    }

    @Test
    void build_adaptiveTrace_mapReduceModeIsAdaptive() {
        ExecutionTrace trace = buildAdaptiveTrace(List.of(
                stubTaskTrace("map A", "Agent-A", "map", 0), stubTaskTrace("final", "Reducer", "final-reduce", 1)));

        DagModel dag = DagExporter.build(trace);

        assertThat(dag.getMapReduceMode()).isEqualTo("ADAPTIVE");
    }

    @Test
    void build_adaptiveTrace_workflowIsPreserved() {
        ExecutionTrace trace = buildAdaptiveTrace(List.of(
                stubTaskTrace("map A", "Agent-A", "map", 0), stubTaskTrace("final", "Reducer", "final-reduce", 1)));

        DagModel dag = DagExporter.build(trace);

        assertThat(dag.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");
    }

    @Test
    void build_adaptiveTrace_taskNodesHaveCorrectNodeTypeAndLevel() {
        List<TaskTrace> traces = List.of(
                stubTaskTrace("map A", "Map-A", "map", 0),
                stubTaskTrace("map B", "Map-B", "map", 0),
                stubTaskTrace("reduce", "Reducer-1", "reduce", 1),
                stubTaskTrace("final", "Final-Reducer", "final-reduce", 2));

        DagModel dag = DagExporter.build(buildAdaptiveTrace(traces));

        assertThat(dag.getTasks()).hasSize(4);
        assertThat(dag.getTasks().get(0).getNodeType()).isEqualTo("map");
        assertThat(dag.getTasks().get(0).getMapReduceLevel()).isEqualTo(0);
        assertThat(dag.getTasks().get(1).getNodeType()).isEqualTo("map");
        assertThat(dag.getTasks().get(2).getNodeType()).isEqualTo("reduce");
        assertThat(dag.getTasks().get(2).getMapReduceLevel()).isEqualTo(1);
        assertThat(dag.getTasks().get(3).getNodeType()).isEqualTo("final-reduce");
        assertThat(dag.getTasks().get(3).getMapReduceLevel()).isEqualTo(2);
    }

    @Test
    void build_adaptiveTrace_parallelGroupsGroupByLevel() {
        List<TaskTrace> traces = List.of(
                stubTaskTrace("map A", "Map-A", "map", 0),
                stubTaskTrace("map B", "Map-B", "map", 0),
                stubTaskTrace("final", "Reducer", "final-reduce", 1));

        DagModel dag = DagExporter.build(buildAdaptiveTrace(traces));

        // Level 0: 2 map tasks, level 1: 1 final reduce
        assertThat(dag.getParallelGroups()).hasSize(2);
        assertThat(dag.getParallelGroups().get(0)).hasSize(2);
        assertThat(dag.getParallelGroups().get(1)).hasSize(1);
    }

    @Test
    void build_adaptiveTrace_agentsFromTraceSummaries() {
        List<TaskTrace> traces = List.of(stubTaskTrace("map A", "Map-A", "map", 0));
        AgentSummary summary = AgentSummary.builder()
                .role("Map-A")
                .goal("map goal")
                .allowDelegation(false)
                .build();
        ExecutionTrace trace = ExecutionTrace.builder()
                .ensembleId("test-id")
                .workflow("MAP_REDUCE_ADAPTIVE")
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .totalDuration(Duration.ZERO)
                .metrics(ExecutionMetrics.EMPTY)
                .taskTrace(traces.get(0))
                .agent(summary)
                .mapReduceLevel(MapReduceLevelSummary.builder()
                        .level(0)
                        .taskCount(1)
                        .duration(Duration.ZERO)
                        .workflow("PARALLEL")
                        .build())
                .build();

        DagModel dag = DagExporter.build(trace);

        assertThat(dag.getAgents()).hasSize(1);
        assertThat(dag.getAgents().get(0).getRole()).isEqualTo("Map-A");
        assertThat(dag.getAgents().get(0).getGoal()).isEqualTo("map goal");
    }

    @Test
    void build_adaptiveTrace_toJson_includesAdaptiveFields() {
        List<TaskTrace> traces = List.of(
                stubTaskTrace("map A", "Map-A", "map", 0), stubTaskTrace("final", "Reducer", "final-reduce", 1));

        String json = DagExporter.build(buildAdaptiveTrace(traces)).toJson();

        assertThat(json).contains("\"mapReduceMode\" : \"ADAPTIVE\"");
        assertThat(json).contains("\"nodeType\" : \"map\"");
        assertThat(json).contains("\"nodeType\" : \"final-reduce\"");
        assertThat(json).contains("\"mapReduceLevel\" : 0");
        assertThat(json).contains("\"mapReduceLevel\" : 1");
    }

    // ========================
    // Short-circuit DAG export (single "direct" node)
    // ========================

    @Test
    void build_shortCircuitTrace_singleNodeWithNodeTypeDirect() {
        // When the short-circuit fires, the ExecutionTrace contains exactly one TaskTrace
        // with nodeType="direct" and mapReduceLevel=0.
        List<TaskTrace> traces = List.of(stubTaskTrace("Direct: handle all items", "Head Chef", "direct", 0));

        DagModel dag = DagExporter.build(buildAdaptiveTrace(traces));

        assertThat(dag.getTasks()).hasSize(1);
        DagTaskNode directNode = dag.getTasks().get(0);
        assertThat(directNode.getNodeType()).isEqualTo("direct");
        assertThat(directNode.getMapReduceLevel()).isEqualTo(0);
    }

    @Test
    void build_shortCircuitTrace_mapReduceModeIsAdaptive() {
        List<TaskTrace> traces = List.of(stubTaskTrace("Direct task", "Head Chef", "direct", 0));

        DagModel dag = DagExporter.build(buildAdaptiveTrace(traces));

        assertThat(dag.getMapReduceMode()).isEqualTo("ADAPTIVE");
    }

    @Test
    void build_shortCircuitTrace_singleParallelGroup() {
        List<TaskTrace> traces = List.of(stubTaskTrace("Direct task", "Head Chef", "direct", 0));

        DagModel dag = DagExporter.build(buildAdaptiveTrace(traces));

        // Single direct node at level 0 -> one parallel group containing one task
        assertThat(dag.getParallelGroups()).hasSize(1);
        assertThat(dag.getParallelGroups().get(0)).hasSize(1);
    }

    @Test
    void build_shortCircuitTrace_workflowIsMapReduceAdaptive() {
        List<TaskTrace> traces = List.of(stubTaskTrace("Direct task", "Head Chef", "direct", 0));

        DagModel dag = DagExporter.build(buildAdaptiveTrace(traces));

        assertThat(dag.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");
    }

    @Test
    void build_shortCircuitTrace_toJson_includesDirectNodeType() {
        List<TaskTrace> traces = List.of(stubTaskTrace("Direct task", "Head Chef", "direct", 0));

        String json = DagExporter.build(buildAdaptiveTrace(traces)).toJson();

        assertThat(json).contains("\"nodeType\" : \"direct\"");
        assertThat(json).contains("\"mapReduceLevel\" : 0");
        assertThat(json).contains("\"mapReduceMode\" : \"ADAPTIVE\"");
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

    /** Build a stub TaskTrace annotated with nodeType and mapReduceLevel. */
    private static TaskTrace stubTaskTrace(String description, String agentRole, String nodeType, int level) {
        return TaskTrace.builder()
                .taskDescription(description)
                .expectedOutput("expected")
                .agentRole(agentRole)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .duration(Duration.ZERO)
                .finalOutput("output of " + description)
                .metrics(TaskMetrics.EMPTY)
                .nodeType(nodeType)
                .mapReduceLevel(level)
                .build();
    }

    /** Build an adaptive ExecutionTrace containing the given task traces. */
    private static ExecutionTrace buildAdaptiveTrace(List<TaskTrace> taskTraces) {
        ExecutionTrace.ExecutionTraceBuilder builder = ExecutionTrace.builder()
                .ensembleId("test-adaptive-id")
                .workflow("MAP_REDUCE_ADAPTIVE")
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .totalDuration(Duration.ZERO)
                .metrics(ExecutionMetrics.EMPTY)
                .mapReduceLevel(MapReduceLevelSummary.builder()
                        .level(0)
                        .taskCount(1)
                        .duration(Duration.ZERO)
                        .workflow("PARALLEL")
                        .build());
        for (TaskTrace t : taskTraces) {
            builder.taskTrace(t);
        }
        return builder.build();
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
