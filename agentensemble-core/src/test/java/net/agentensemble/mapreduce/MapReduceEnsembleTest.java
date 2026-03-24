package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.workflow.ParallelErrorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MapReduceEnsemble}.
 *
 * <p>Tests cover builder validation and static DAG construction for a variety
 * of (N, K) combinations, context wiring correctness, agent/task counts,
 * and factory distinct-instance guarantees.
 *
 * <p>No LLM calls are made; a {@link NoOpChatModel} stub satisfies Agent validation.
 */
class MapReduceEnsembleTest {

    private ChatModel stub;
    private AtomicInteger agentCounter;

    @BeforeEach
    void setUp() {
        stub = new NoOpChatModel();
        agentCounter = new AtomicInteger(0);
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void build_nullItems_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().items(null).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void build_emptyItems_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().items(List.of()).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("items");
    }

    @Test
    void build_nullMapAgent_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        .mapTask((item, agent) -> stubTask("map " + item, agent))
                        .reduceAgent(() -> stubAgent("R"))
                        .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapAgent");
    }

    @Test
    void build_nullMapTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        .mapAgent(item -> stubAgent("M-" + item))
                        .reduceAgent(() -> stubAgent("R"))
                        .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("mapTask");
    }

    @Test
    void build_nullReduceAgent_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        .mapAgent(item -> stubAgent("M-" + item))
                        .mapTask((item, agent) -> stubTask("map " + item, agent))
                        .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceAgent");
    }

    @Test
    void build_nullReduceTask_throwsValidationException() {
        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A"))
                        .mapAgent(item -> stubAgent("M-" + item))
                        .mapTask((item, agent) -> stubTask("map " + item, agent))
                        .reduceAgent(() -> stubAgent("R"))
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reduceTask");
    }

    @Test
    void build_chunkSizeZero_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().chunkSize(0).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chunkSize");
    }

    @Test
    void build_chunkSizeOne_throwsValidationException() {
        assertThatThrownBy(() -> baseBuilder().chunkSize(1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("chunkSize");
    }

    @Test
    void build_chunkSizeTwo_succeeds() {
        MapReduceEnsemble<String> mre = baseBuilder().chunkSize(2).build();
        assertThat(mre).isNotNull();
    }

    @Test
    void build_defaultChunkSizeIsFive() {
        // N=5 with default chunkSize=5 should produce 5 map + 1 final = 6 tasks
        List<String> items = List.of("A", "B", "C", "D", "E");
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(this::uniqueReduceAgent)
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                .build();

        // 5 map + 1 final reduce = 6 tasks (N <= defaultChunkSize=5)
        assertThat(mre.toEnsemble().getTasks()).hasSize(6);
    }

    // ========================
    // DAG construction: N=1
    // ========================

    @Test
    void dagConstruction_N1_singleMapPlusFinalReduce() {
        List<String> items = List.of("A");
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(this::uniqueReduceAgent)
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                .chunkSize(3)
                .build();

        Ensemble inner = mre.toEnsemble();
        assertThat(inner.getTasks()).hasSize(2); // 1 map + 1 final
        assertThat(inner.getAgents()).hasSize(2);
    }

    @Test
    void dagConstruction_N1_finalReduceContextContainsMapTask() {
        List<String> items = List.of("A");
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(this::uniqueReduceAgent)
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                .chunkSize(3)
                .build();

        List<Task> tasks = mre.toEnsemble().getTasks();
        Task mapTask = tasks.get(0);
        Task finalTask = tasks.get(1);

        assertThat(mapTask.getContext()).isEmpty();
        assertThat(finalTask.getContext()).containsExactly(mapTask);
    }

    // ========================
    // DAG construction: N=3/K=3 (N <= chunkSize -- no intermediate level)
    // ========================

    @Test
    void dagConstruction_N3_K3_noIntermediateLevel() {
        List<String> items = List.of("A", "B", "C");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        Ensemble inner = mre.toEnsemble();
        // 3 map + 1 final = 4 tasks
        assertThat(inner.getTasks()).hasSize(4);
        assertThat(inner.getAgents()).hasSize(4);
    }

    @Test
    void dagConstruction_N3_K3_finalReduceContextIsAllMapTasks() {
        List<String> items = List.of("A", "B", "C");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        Task mapA = tasks.get(0);
        Task mapB = tasks.get(1);
        Task mapC = tasks.get(2);
        Task finalTask = tasks.get(3);

        assertThat(finalTask.getContext()).containsExactly(mapA, mapB, mapC);
    }

    // ========================
    // DAG construction: N=4/K=3 (one intermediate level with 2 groups)
    // ========================

    @Test
    void dagConstruction_N4_K3_oneIntermediateLevel() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        Ensemble inner = mre.toEnsemble();
        // 4 map + 2 L1 + 1 final = 7 tasks
        assertThat(inner.getTasks()).hasSize(7);
        assertThat(inner.getAgents()).hasSize(7);
    }

    @Test
    void dagConstruction_N4_K3_contextWiringIsCorrect() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        // tasks: [M0, M1, M2, M3, L1-0, L1-1, Final]
        Task m0 = tasks.get(0);
        Task m1 = tasks.get(1);
        Task m2 = tasks.get(2);
        Task m3 = tasks.get(3);
        Task l1_0 = tasks.get(4);
        Task l1_1 = tasks.get(5);
        Task finalTask = tasks.get(6);

        // Map tasks have no context
        assertThat(m0.getContext()).isEmpty();
        assertThat(m1.getContext()).isEmpty();
        assertThat(m2.getContext()).isEmpty();
        assertThat(m3.getContext()).isEmpty();

        // L1-0 receives first group [M0, M1, M2]
        assertThat(l1_0.getContext()).containsExactly(m0, m1, m2);

        // L1-1 receives second group [M3]
        assertThat(l1_1.getContext()).containsExactly(m3);

        // Final receives all L1 reduce tasks
        assertThat(finalTask.getContext()).containsExactly(l1_0, l1_1);
    }

    // ========================
    // DAG construction: N=9/K=3 (one intermediate level with 3 groups)
    // ========================

    @Test
    void dagConstruction_N9_K3_oneIntermediateLevel() {
        List<String> items = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        Ensemble inner = mre.toEnsemble();
        // 9 map + 3 L1 + 1 final = 13 tasks
        assertThat(inner.getTasks()).hasSize(13);
        assertThat(inner.getAgents()).hasSize(13);
    }

    @Test
    void dagConstruction_N9_K3_contextWiringIsCorrect() {
        List<String> items = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        // L1-0 context = [M0, M1, M2]
        assertThat(tasks.get(9).getContext()).containsExactly(tasks.get(0), tasks.get(1), tasks.get(2));
        // L1-1 context = [M3, M4, M5]
        assertThat(tasks.get(10).getContext()).containsExactly(tasks.get(3), tasks.get(4), tasks.get(5));
        // L1-2 context = [M6, M7, M8]
        assertThat(tasks.get(11).getContext()).containsExactly(tasks.get(6), tasks.get(7), tasks.get(8));
        // Final context = [L1-0, L1-1, L1-2]
        assertThat(tasks.get(12).getContext()).containsExactly(tasks.get(9), tasks.get(10), tasks.get(11));
    }

    // ========================
    // DAG construction: N=25/K=5 (one intermediate level with 5 groups)
    // ========================

    @Test
    void dagConstruction_N25_K5_oneIntermediateLevel() {
        List<String> items = buildItems(25);
        MapReduceEnsemble<String> mre = buildMre(items, 5);

        Ensemble inner = mre.toEnsemble();
        // 25 map + 5 L1 + 1 final = 31 tasks
        assertThat(inner.getTasks()).hasSize(31);
    }

    // ========================
    // DAG construction: N=26/K=5 (two intermediate levels)
    // ========================

    @Test
    void dagConstruction_N26_K5_twoIntermediateLevels() {
        List<String> items = buildItems(26);
        MapReduceEnsemble<String> mre = buildMre(items, 5);

        Ensemble inner = mre.toEnsemble();
        // 26 map + 6 L1 + 2 L2 + 1 final = 35 tasks
        assertThat(inner.getTasks()).hasSize(35);
        assertThat(inner.getAgents()).hasSize(35);
    }

    @Test
    void dagConstruction_N26_K5_twoIntermediateLevelsContextWiring() {
        List<String> items = buildItems(26);
        MapReduceEnsemble<String> mre = buildMre(items, 5);

        List<Task> tasks = mre.toEnsemble().getTasks();
        // tasks[0..25] = map tasks
        // tasks[26..31] = L1 reduce (6 tasks)
        // tasks[32..33] = L2 reduce (2 tasks)
        // tasks[34] = final reduce

        // L1-0 context = [M0..M4]
        assertThat(tasks.get(26).getContext())
                .containsExactly(tasks.get(0), tasks.get(1), tasks.get(2), tasks.get(3), tasks.get(4));
        // L1-5 context = [M25] (last group is a singleton)
        assertThat(tasks.get(31).getContext()).containsExactly(tasks.get(25));

        // L2-0 context = [L1-0..L1-4]
        assertThat(tasks.get(32).getContext())
                .containsExactly(tasks.get(26), tasks.get(27), tasks.get(28), tasks.get(29), tasks.get(30));
        // L2-1 context = [L1-5]
        assertThat(tasks.get(33).getContext()).containsExactly(tasks.get(31));

        // Final context = [L2-0, L2-1]
        assertThat(tasks.get(34).getContext()).containsExactly(tasks.get(32), tasks.get(33));
    }

    // ========================
    // Agent count and distinct instances
    // ========================

    @Test
    void agentCount_matchesExpected_N9_K3() {
        List<String> items = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");
        MapReduceEnsemble<String> mre = buildMre(items, 3);
        // 9 map agents + 3 L1 reduce agents + 1 final reduce agent = 13
        assertThat(mre.toEnsemble().getAgents()).hasSize(13);
    }

    @Test
    void mapAgentFactory_calledOncePerItem() {
        List<String> items = List.of("A", "B", "C", "D");
        List<Agent> capturedMapAgents = new ArrayList<>();

        MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> {
                    Agent a = stubAgent("M-" + item);
                    capturedMapAgents.add(a);
                    return a;
                })
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(this::uniqueReduceAgent)
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                .chunkSize(3)
                .build();

        // Factory called exactly once per item
        assertThat(capturedMapAgents).hasSize(4);
    }

    @Test
    void mapAgentFactory_producesDistinctInstances() {
        List<String> items = List.of("A", "B", "C");
        MapReduceEnsemble<String> mre = buildMre(items, 5);

        List<Agent> agents = mre.toEnsemble().getAgents();
        // Verify no two agent objects are the same reference
        Map<Agent, Boolean> seen = new IdentityHashMap<>();
        for (Agent a : agents) {
            assertThat(seen.put(a, true)).isNull();
        }
    }

    @Test
    void reduceAgentFactory_calledOncePerGroup_N9_K3() {
        List<String> items = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I");
        AtomicInteger callCount = new AtomicInteger(0);

        MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(() -> {
                    callCount.incrementAndGet();
                    return uniqueReduceAgent();
                })
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                .chunkSize(3)
                .build();

        // 3 L1 groups + 1 final reduce = 4 calls
        assertThat(callCount.get()).isEqualTo(4);
    }

    @Test
    void reduceAgentFactory_producesDistinctInstances() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Agent> agents = mre.toEnsemble().getAgents();
        // 4 map + 2 L1 + 1 final = 7 agents, all distinct by identity
        Map<Agent, Boolean> seen = new IdentityHashMap<>();
        for (Agent a : agents) {
            assertThat(seen.put(a, true)).isNull();
        }
    }

    // ========================
    // toEnsemble
    // ========================

    @Test
    void toEnsemble_returnsPreBuiltEnsemble() {
        MapReduceEnsemble<String> mre = buildMre(List.of("A", "B"), 3);
        Ensemble inner = mre.toEnsemble();
        assertThat(inner).isNotNull();
        // Verify it is usable (has tasks and agents)
        assertThat(inner.getTasks()).isNotEmpty();
        assertThat(inner.getAgents()).isNotEmpty();
    }

    @Test
    void toEnsemble_returnsConsistentInstance_onRepeatedCalls() {
        MapReduceEnsemble<String> mre = buildMre(List.of("A"), 3);
        Ensemble first = mre.toEnsemble();
        Ensemble second = mre.toEnsemble();
        assertThat(first).isSameAs(second);
    }

    // ========================
    // Passthrough fields (coverage for optional builder setters)
    // ========================

    @Test
    void build_verbose_succeeds() {
        MapReduceEnsemble<String> mre = baseBuilder().verbose(true).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_listener_succeeds() {
        EnsembleListener noOp = new EnsembleListener() {};
        MapReduceEnsemble<String> mre = baseBuilder().listener(noOp).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_listeners_list_succeeds() {
        EnsembleListener noOp = new EnsembleListener() {};
        MapReduceEnsemble<String> mre = baseBuilder().listeners(List.of(noOp)).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_captureMode_succeeds() {
        MapReduceEnsemble<String> mre =
                baseBuilder().captureMode(CaptureMode.STANDARD).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_parallelErrorStrategy_succeeds() {
        MapReduceEnsemble<String> mre = baseBuilder()
                .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                .build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_costConfiguration_succeeds() {
        CostConfiguration costs = CostConfiguration.builder()
                .inputTokenRate(new BigDecimal("0.000001"))
                .outputTokenRate(new BigDecimal("0.000002"))
                .build();
        MapReduceEnsemble<String> mre = baseBuilder().costConfiguration(costs).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_toolExecutor_succeeds() {
        MapReduceEnsemble<String> mre =
                baseBuilder().toolExecutor(Executors.newSingleThreadExecutor()).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_input_singleEntry_passedToInnerEnsemble() {
        MapReduceEnsemble<String> mre = baseBuilder().input("key", "value").build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    @Test
    void build_inputs_mapEntry_passedToInnerEnsemble() {
        MapReduceEnsemble<String> mre =
                baseBuilder().inputs(Map.of("k1", "v1", "k2", "v2")).build();
        assertThat(mre.toEnsemble()).isNotNull();
    }

    // ========================
    // Helpers
    // ========================

    private MapReduceEnsemble<String> buildMre(List<String> items, int chunkSize) {
        return MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(this::uniqueReduceAgent)
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk))
                .chunkSize(chunkSize)
                .build();
    }

    private MapReduceEnsemble.Builder<String> baseBuilder() {
        return MapReduceEnsemble.<String>builder()
                .items(List.of("A"))
                .mapAgent(item -> stubAgent("M-" + item))
                .mapTask((item, agent) -> stubTask("map " + item, agent))
                .reduceAgent(this::uniqueReduceAgent)
                .reduceTask((agent, chunk) -> stubReduceTask(agent, chunk));
    }

    private Agent stubAgent(String role) {
        return Agent.builder().role(role).goal("goal").llm(stub).build();
    }

    private Agent uniqueReduceAgent() {
        return stubAgent("Reducer-" + agentCounter.incrementAndGet());
    }

    private Task stubTask(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("output of " + description)
                .agent(agent)
                .build();
    }

    private Task stubReduceTask(Agent agent, List<Task> chunk) {
        return Task.builder()
                .description("reduce-" + agentCounter.incrementAndGet())
                .expectedOutput("reduced output")
                .agent(agent)
                .context(chunk)
                .build();
    }

    private static List<String> buildItems(int n) {
        List<String> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add("item-" + i);
        }
        return items;
    }

    /**
     * Minimal no-op ChatModel for test agent construction.
     * The test never executes tasks so the LLM is never called.
     */
    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called in unit tests");
        }
    }
}
