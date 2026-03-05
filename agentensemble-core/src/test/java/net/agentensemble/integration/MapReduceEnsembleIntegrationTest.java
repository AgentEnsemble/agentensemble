package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.workflow.ParallelErrorStrategy;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link MapReduceEnsemble} with mock LLMs.
 *
 * <p>Tests cover end-to-end execution, agent call counts, FAIL_FAST and CONTINUE_ON_ERROR
 * error strategies, and run-time input overrides.
 */
class MapReduceEnsembleIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithResponse(String role, String response) {
        ChatModel mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    private Agent agentThatFails(String role) {
        ChatModel mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM failure for " + role));
        return Agent.builder().role(role).goal("Do work").llm(mockLlm).build();
    }

    // ========================
    // Basic end-to-end execution
    // ========================

    @Test
    void run_N6_K3_completesSuccessfully() {
        // 6 items, chunkSize=3: 6 map + 2 L1 reduce + 1 final = 9 tasks
        List<String> items = List.of("dish-1", "dish-2", "dish-3", "dish-4", "dish-5", "dish-6");
        AtomicInteger mapCallCount = new AtomicInteger(0);
        AtomicInteger reduceCallCount = new AtomicInteger(0);

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> {
                    mapCallCount.incrementAndGet();
                    return agentWithResponse("Chef-" + item, "Recipe for " + item);
                })
                .mapTask((item, agent) -> Task.builder()
                        .description("Prepare " + item)
                        .expectedOutput("Preparation for " + item)
                        .agent(agent)
                        .build())
                .reduceAgent(() -> {
                    reduceCallCount.incrementAndGet();
                    return agentWithResponse(
                            "Reducer-" + reduceCallCount.get(),
                            "Consolidated plan from reducer " + reduceCallCount.get());
                })
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Consolidate preparations")
                        .expectedOutput("Consolidated plan")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build();

        var output = mre.run();

        // Run completed
        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isNotBlank();

        // The final output comes from the final reduce task
        assertThat(output.getTaskOutputs()).hasSize(9);

        // DAG structure is correct
        assertThat(mre.toEnsemble().getTasks()).hasSize(9);
        assertThat(mre.toEnsemble().getAgents()).hasSize(9);

        // Map factory called once per item (at build time, not run time)
        assertThat(mapCallCount.get()).isEqualTo(6);
        // Reduce factory called for 2 L1 groups + 1 final = 3 times (at build time)
        assertThat(reduceCallCount.get()).isEqualTo(3);
    }

    @Test
    void run_N3_K5_noIntermediateLevel_completesSuccessfully() {
        // 3 items, chunkSize=5: 3 map + 1 final = 4 tasks (no intermediate level)
        List<String> items = List.of("A", "B", "C");

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithResponse("Map-" + item, "map output " + item))
                .mapTask((item, agent) -> Task.builder()
                        .description("Map " + item)
                        .expectedOutput("Mapped")
                        .agent(agent)
                        .build())
                .reduceAgent(() -> agentWithResponse("Reducer", "final reduced output"))
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Final reduce")
                        .expectedOutput("Reduced")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build();

        var output = mre.run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isEqualTo("final reduced output");
        assertThat(output.getTaskOutputs()).hasSize(4); // 3 map + 1 final
    }

    @Test
    void run_N1_K3_completesSuccessfully() {
        // N=1: 1 map + 1 final = 2 tasks
        List<String> items = List.of("only-item");

        var output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithResponse("Map-" + item, "mapped: " + item))
                .mapTask((item, agent) -> Task.builder()
                        .description("Map " + item)
                        .expectedOutput("Mapped")
                        .agent(agent)
                        .build())
                .reduceAgent(() -> agentWithResponse("Reducer", "final result"))
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Reduce")
                        .expectedOutput("Reduced")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("final result");
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    @Test
    void run_returnsOutputFromFinalReduceTask() {
        // The raw output should be from the final reduce (last task in topological order)
        List<String> items = List.of("X", "Y");

        var output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithResponse("Map-" + item, "map:" + item))
                .mapTask((item, agent) -> Task.builder()
                        .description("Map " + item)
                        .expectedOutput("Mapped")
                        .agent(agent)
                        .build())
                .reduceAgent(() -> agentWithResponse("Reducer", "FINAL_OUTPUT"))
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Final reduce")
                        .expectedOutput("Final")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("FINAL_OUTPUT");
    }

    // ========================
    // run(Map) - runtime inputs
    // ========================

    @Test
    void run_withRuntimeInputs_templateVariablesResolved() {
        List<String> items = List.of("pasta");

        var output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithResponse("Chef", "recipe for " + item + " cuisine"))
                .mapTask((item, agent) -> Task.builder()
                        .description("Prepare {cuisine} " + item)
                        .expectedOutput("Recipe")
                        .agent(agent)
                        .build())
                .reduceAgent(() -> agentWithResponse("Head Chef", "consolidated"))
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Consolidate")
                        .expectedOutput("Done")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build()
                .run(Map.of("cuisine", "Italian"));

        assertThat(output).isNotNull();
        // The task description was resolved (verified by the fact that execution succeeded)
        assertThat(output.getTaskOutputs()).hasSize(2);
    }

    // ========================
    // FAIL_FAST error strategy
    // ========================

    @Test
    void run_failFast_oneMapTaskFails_throwsTaskExecutionException() {
        List<String> items = List.of("good-1", "bad", "good-2");

        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(items)
                        .mapAgent(item -> item.equals("bad")
                                ? agentThatFails("Bad-Chef")
                                : agentWithResponse("Chef-" + item, "result for " + item))
                        .mapTask((item, agent) -> Task.builder()
                                .description("Map " + item)
                                .expectedOutput("Mapped")
                                .agent(agent)
                                .build())
                        .reduceAgent(() -> agentWithResponse("Reducer", "reduced"))
                        .reduceTask((agent, chunkTasks) -> Task.builder()
                                .description("Reduce")
                                .expectedOutput("Reduced")
                                .agent(agent)
                                .context(chunkTasks)
                                .build())
                        .chunkSize(5)
                        .parallelErrorStrategy(ParallelErrorStrategy.FAIL_FAST)
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class);
    }

    @Test
    void run_failFast_isDefaultStrategy() {
        List<String> items = List.of("ok", "fail");

        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(items)
                        .mapAgent(item -> item.equals("fail") ? agentThatFails("Fail") : agentWithResponse("OK", "ok"))
                        .mapTask((item, agent) -> Task.builder()
                                .description("Map " + item)
                                .expectedOutput("Out")
                                .agent(agent)
                                .build())
                        .reduceAgent(() -> agentWithResponse("Reducer", "reduced"))
                        .reduceTask((agent, chunkTasks) -> Task.builder()
                                .description("Reduce")
                                .expectedOutput("Reduced")
                                .agent(agent)
                                .context(chunkTasks)
                                .build())
                        .chunkSize(5)
                        // no explicit parallelErrorStrategy -> default is FAIL_FAST
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class);
    }

    // ========================
    // CONTINUE_ON_ERROR strategy
    // ========================

    @Test
    void run_continueOnError_oneMapTaskFails_throwsParallelExecutionException() {
        // With CONTINUE_ON_ERROR, surviving outputs proceed but since the reduce task
        // depends on the failed map output, the executor will throw ParallelExecutionException
        // reporting the partial results.
        List<String> items = List.of("good", "bad");

        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(items)
                        .mapAgent(
                                item -> item.equals("bad") ? agentThatFails("Bad") : agentWithResponse("Good", "good"))
                        .mapTask((item, agent) -> Task.builder()
                                .description("Map " + item)
                                .expectedOutput("Out")
                                .agent(agent)
                                .build())
                        .reduceAgent(() -> agentWithResponse("Reducer", "reduced"))
                        .reduceTask((agent, chunkTasks) -> Task.builder()
                                .description("Reduce")
                                .expectedOutput("Reduced")
                                .agent(agent)
                                .context(chunkTasks)
                                .build())
                        .chunkSize(5)
                        .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                        .build()
                        .run())
                .isInstanceOf(ParallelExecutionException.class)
                .satisfies(ex -> {
                    ParallelExecutionException pe = (ParallelExecutionException) ex;
                    assertThat(pe.getCompletedCount()).isGreaterThanOrEqualTo(1);
                    assertThat(pe.getFailedCount()).isGreaterThanOrEqualTo(1);
                });
    }

    // ========================
    // toEnsemble() for devtools inspection
    // ========================

    @Test
    void toEnsemble_N6_K3_hasCorrectStructure() {
        List<String> items = List.of("A", "B", "C", "D", "E", "F");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        Ensemble inner = mre.toEnsemble();
        // 6 map + 2 L1 + 1 final = 9
        assertThat(inner.getTasks()).hasSize(9);
        assertThat(inner.getAgents()).hasSize(9);
    }

    @Test
    void nodeTypes_map_areCorrect() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        // Map tasks
        for (int i = 0; i < 4; i++) {
            assertThat(mre.getNodeTypes().get(tasks.get(i))).isEqualTo(MapReduceEnsemble.NODE_TYPE_MAP);
        }
    }

    @Test
    void nodeTypes_intermediateReduce_areCorrect() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        // L1 reduce tasks (index 4 and 5)
        assertThat(mre.getNodeTypes().get(tasks.get(4))).isEqualTo(MapReduceEnsemble.NODE_TYPE_REDUCE);
        assertThat(mre.getNodeTypes().get(tasks.get(5))).isEqualTo(MapReduceEnsemble.NODE_TYPE_REDUCE);
    }

    @Test
    void nodeTypes_finalReduce_isCorrect() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        Task finalTask = tasks.get(tasks.size() - 1);
        assertThat(mre.getNodeTypes().get(finalTask)).isEqualTo(MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE);
    }

    @Test
    void mapReduceLevels_areCorrect_N4_K3() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = buildMre(items, 3);

        List<Task> tasks = mre.toEnsemble().getTasks();
        // Map tasks at level 0
        for (int i = 0; i < 4; i++) {
            assertThat(mre.getMapReduceLevels().get(tasks.get(i))).isEqualTo(0);
        }
        // L1 reduce at level 1
        assertThat(mre.getMapReduceLevels().get(tasks.get(4))).isEqualTo(1);
        assertThat(mre.getMapReduceLevels().get(tasks.get(5))).isEqualTo(1);
        // Final at level 2
        assertThat(mre.getMapReduceLevels().get(tasks.get(6))).isEqualTo(2);
    }

    // ========================
    // Helpers
    // ========================

    private MapReduceEnsemble<String> buildMre(List<String> items, int chunkSize) {
        AtomicInteger counter = new AtomicInteger(0);
        return MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithResponse("Map-" + item, "map output: " + item))
                .mapTask((item, agent) -> Task.builder()
                        .description("Map " + item)
                        .expectedOutput("Mapped")
                        .agent(agent)
                        .build())
                .reduceAgent(() -> agentWithResponse(
                        "Reducer-" + counter.incrementAndGet(), "reduced by reducer " + counter.get()))
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Reduce-" + counter.get())
                        .expectedOutput("Reduced")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(chunkSize)
                .build();
    }
}
