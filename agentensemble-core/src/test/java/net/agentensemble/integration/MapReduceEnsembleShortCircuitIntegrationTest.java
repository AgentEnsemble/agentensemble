package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.TaskTrace;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the short-circuit optimization in adaptive {@link MapReduceEnsemble}.
 *
 * <p>Uses mock {@link ChatModel}s (Mockito) to control LLM responses and verify call counts.
 *
 * <p>Scenarios:
 * <ul>
 *   <li>3 items, small input fits in budget, direct factories configured: single LLM call</li>
 *   <li>3 items, small input fits in budget, no direct factories: normal map-reduce (4 LLM calls)</li>
 *   <li>10 items, large input exceeds budget, direct factories configured: normal map-reduce</li>
 *   <li>Direct task receives all items in {@code List<T>} argument</li>
 *   <li>{@code EnsembleOutput.getRaw()} equals the direct task output</li>
 *   <li>Custom {@code inputEstimator} changes the short-circuit decision</li>
 * </ul>
 */
class MapReduceEnsembleShortCircuitIntegrationTest {

    /** Small item: 40 chars = 10 heuristic tokens (4 chars per token). */
    private static final String SMALL_ITEM = "x".repeat(40);

    /** Large item: 400 chars = 100 heuristic tokens. */
    private static final String LARGE_ITEM = "x".repeat(400);

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private Agent agentWithModel(String role, ChatModel model) {
        return Agent.builder().role(role).goal("Do work").llm(model).build();
    }

    // ========================
    // 3 items, small input, direct factories: single LLM call, 1 TaskOutput
    // ========================

    @Test
    void run_threeSmallItems_withDirectFactories_shortCircuitFires_singleLlmCall() {
        // 3 items of 40 chars each -> 10 tokens each -> 30 total <= budget(100)
        // Short-circuit fires: exactly 1 LLM call to the direct agent
        ChatModel directModel = mock(ChatModel.class);
        when(directModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct result for all 3 dishes"));

        // mapAgent and reduceAgent models must NOT be called
        ChatModel mapModel = mock(ChatModel.class);
        ChatModel reduceModel = mock(ChatModel.class);

        List<String> items = List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithModel("Map", mapModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map " + item.length())
                        .expectedOutput("map output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce", reduceModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("reduced")
                        .agent(a)
                        .context(chunk)
                        .build())
                .directAgent(() -> agentWithModel("Head Chef", directModel))
                .directTask((a, allItems) -> Task.builder()
                        .description("Direct: handle all " + allItems.size() + " dishes")
                        .expectedOutput("Complete meal plan")
                        .agent(a)
                        .build())
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isEqualTo("Direct result for all 3 dishes");
        assertThat(output.getTaskOutputs()).hasSize(1);

        // Direct model called exactly once
        verify(directModel, times(1)).chat(any(ChatRequest.class));
        // Map and reduce models never called
        verify(mapModel, times(0)).chat(any(ChatRequest.class));
        verify(reduceModel, times(0)).chat(any(ChatRequest.class));
    }

    @Test
    void run_threeSmallItems_withDirectFactories_ensembleOutputRawIsDirectTaskOutput() {
        String directOutput = "Consolidated meal plan for all dishes";
        ChatModel directModel = mock(ChatModel.class);
        when(directModel.chat(any(ChatRequest.class))).thenReturn(textResponse(directOutput));

        List<String> items = List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM);
        ChatModel unusedModel = mock(ChatModel.class);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithModel("Map", unusedModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce", unusedModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("output")
                        .agent(a)
                        .context(chunk)
                        .build())
                .directAgent(() -> agentWithModel("Head Chef", directModel))
                .directTask((a, allItems) -> Task.builder()
                        .description("direct")
                        .expectedOutput("direct output")
                        .agent(a)
                        .build())
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo(directOutput);
    }

    // ========================
    // 3 items, small input, NO direct factories: normal map-reduce (3 map + 1 reduce)
    // ========================

    @Test
    void run_threeSmallItems_noDirectFactories_normalMapReduceExecutes() {
        // 3 items fit in budget(100) but no direct factories -> normal map-reduce runs
        // Expect 3 map LLM calls + 1 reduce LLM call = 4 total
        ChatModel mapModel = mock(ChatModel.class);
        when(mapModel.chat(any(ChatRequest.class))).thenReturn(textResponse("map output"));

        ChatModel reduceModel = mock(ChatModel.class);
        when(reduceModel.chat(any(ChatRequest.class))).thenReturn(textResponse("final reduce output"));

        List<String> items = List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithModel("Map", mapModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce", reduceModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("output")
                        .agent(a)
                        .context(chunk)
                        .build())
                // No directAgent/directTask
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("final reduce output");
        // Normal map-reduce: 3 map + 1 final reduce = 4 task outputs
        assertThat(output.getTaskOutputs()).hasSize(4);

        // Map model called 3 times (one per item)
        verify(mapModel, times(3)).chat(any(ChatRequest.class));
        // Reduce model called 1 time (final reduce)
        verify(reduceModel, times(1)).chat(any(ChatRequest.class));
    }

    // ========================
    // 10 items, large input > budget, direct factories: normal map-reduce
    // ========================

    @Test
    void run_tenLargeItems_exceedsBudget_directFactoriesConfigured_normalMapReduceExecutes() {
        // 10 items of 400 chars -> 100 tokens each -> 1000 total > budget(500)
        // Short-circuit does NOT fire; normal adaptive map-reduce runs
        // Budget = 500 -> map outputs (all short "z"*4 = 1 token each) fit in budget ->
        // 10 map + 1 final reduce = 11 LLM calls

        ChatModel mapModel = mock(ChatModel.class);
        // Short map output: 4 chars = 1 token each; 10 total = 10 tokens <= budget(500)
        when(mapModel.chat(any(ChatRequest.class))).thenReturn(textResponse("zzzz"));

        ChatModel reduceModel = mock(ChatModel.class);
        when(reduceModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Final consolidated result"));

        ChatModel directModel = mock(ChatModel.class);
        // Direct model must NOT be called (short-circuit doesn't fire)

        AtomicInteger reduceCounter = new AtomicInteger(0);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(LARGE_ITEM);
        }

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithModel("Map", mapModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce-" + reduceCounter.incrementAndGet(), reduceModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("output")
                        .agent(a)
                        .context(chunk)
                        .build())
                .directAgent(() -> agentWithModel("Direct", directModel))
                .directTask((a, allItems) -> Task.builder()
                        .description("direct")
                        .expectedOutput("direct output")
                        .agent(a)
                        .build())
                .targetTokenBudget(500)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Final consolidated result");

        // Direct model was never called (short-circuit did not fire)
        verify(directModel, times(0)).chat(any(ChatRequest.class));

        // Map model called 10 times, reduce model called at least once
        verify(mapModel, times(10)).chat(any(ChatRequest.class));
        verify(reduceModel, times(1)).chat(any(ChatRequest.class));
    }

    // ========================
    // Direct task receives all items in List<T>
    // ========================

    @Test
    void run_shortCircuitFires_directTaskReceivesAllItemsInList() {
        List<String> inputItems = List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM);
        AtomicReference<List<String>> capturedItems = new AtomicReference<>();

        ChatModel directModel = mock(ChatModel.class);
        when(directModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct output"));
        ChatModel unusedModel = mock(ChatModel.class);

        MapReduceEnsemble.<String>builder()
                .items(inputItems)
                .mapAgent(item -> agentWithModel("Map", unusedModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce", unusedModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("output")
                        .agent(a)
                        .context(chunk)
                        .build())
                .directAgent(() -> agentWithModel("Direct", directModel))
                .directTask((a, allItems) -> {
                    capturedItems.set(new ArrayList<>(allItems));
                    return Task.builder()
                            .description("direct with " + allItems.size() + " items")
                            .expectedOutput("output")
                            .agent(a)
                            .build();
                })
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(capturedItems.get()).isNotNull();
        assertThat(capturedItems.get()).hasSize(3);
        assertThat(capturedItems.get()).containsExactlyElementsOf(inputItems);
    }

    // ========================
    // Short-circuit with custom inputEstimator
    // ========================

    @Test
    void run_customInputEstimator_shortCircuitFiresDespiteLargeToString() {
        // Items have large toString() (400 chars = 100 tokens) but compact inputEstimator (1 char = 0 tokens)
        // -> short-circuit fires even though toString() would exceed budget
        List<String> items = List.of(LARGE_ITEM, LARGE_ITEM, LARGE_ITEM);

        ChatModel directModel = mock(ChatModel.class);
        when(directModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Compact estimator direct result"));

        ChatModel unusedModel = mock(ChatModel.class);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(items)
                .mapAgent(item -> agentWithModel("Map", unusedModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce", unusedModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("output")
                        .agent(a)
                        .context(chunk)
                        .build())
                .directAgent(() -> agentWithModel("Direct", directModel))
                .directTask((a, allItems) -> Task.builder()
                        .description("direct")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .inputEstimator(item -> "x") // 1 char = 0 tokens per item -> 0 total
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Compact estimator direct result");
        assertThat(output.getTaskOutputs()).hasSize(1);

        verify(directModel, times(1)).chat(any(ChatRequest.class));
        verify(unusedModel, times(0)).chat(any(ChatRequest.class));
    }

    // ========================
    // Short-circuit trace and DAG
    // ========================

    @Test
    void run_shortCircuitFires_traceWorkflowIsAdaptive_singleTaskTrace() {
        ChatModel directModel = mock(ChatModel.class);
        when(directModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct output"));
        ChatModel unusedModel = mock(ChatModel.class);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agentWithModel("Map", unusedModel))
                .mapTask((item, a) -> Task.builder()
                        .description("map")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .reduceAgent(() -> agentWithModel("Reduce", unusedModel))
                .reduceTask((a, chunk) -> Task.builder()
                        .description("reduce")
                        .expectedOutput("output")
                        .agent(a)
                        .context(chunk)
                        .build())
                .directAgent(() -> agentWithModel("Head Chef", directModel))
                .directTask((a, allItems) -> Task.builder()
                        .description("direct for all items")
                        .expectedOutput("output")
                        .agent(a)
                        .build())
                .targetTokenBudget(100)
                .build()
                .run();

        ExecutionTrace trace = output.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");
        assertThat(trace.getMapReduceLevels()).hasSize(1);

        List<TaskTrace> taskTraces = trace.getTaskTraces();
        assertThat(taskTraces).hasSize(1);
        assertThat(taskTraces.get(0).getNodeType()).isEqualTo(MapReduceEnsemble.NODE_TYPE_DIRECT);
        assertThat(taskTraces.get(0).getMapReduceLevel()).isEqualTo(0);
    }
}
