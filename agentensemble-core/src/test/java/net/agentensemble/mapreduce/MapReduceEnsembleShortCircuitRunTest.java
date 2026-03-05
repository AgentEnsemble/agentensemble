package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.MapReduceLevelSummary;
import net.agentensemble.trace.TaskTrace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the short-circuit optimization in adaptive {@link MapReduceEnsemble}.
 *
 * <p>Token estimation heuristic: {@code text.length() / 4} (integer division).
 * All tests use mock LLMs to avoid real network calls.
 *
 * <p>Covers:
 * <ul>
 *   <li>Input estimation: sum of {@code toString().length() / 4} for all items</li>
 *   <li>Short-circuit fires when estimated input {@code <=} budget AND both factories present</li>
 *   <li>Short-circuit does NOT fire when estimated input {@code >} budget</li>
 *   <li>Short-circuit does NOT fire when direct factories are not configured</li>
 *   <li>N=1 item, fits in budget: direct task fires</li>
 *   <li>N=1 item, exceeds budget: normal map-reduce runs</li>
 *   <li>All items produce empty text representation: estimated = 0 {@code <=} any budget</li>
 *   <li>Custom {@code inputEstimator} used instead of {@code toString()}</li>
 *   <li>Boundary: estimated exactly equals budget -- short-circuit fires (inclusive)</li>
 *   <li>Direct task receives the complete {@code List<T>} of all items</li>
 *   <li>Trace: {@code workflow = "MAP_REDUCE_ADAPTIVE"}, single {@code TaskTrace}
 *       with {@code nodeType = "direct"} and {@code mapReduceLevel = 0}</li>
 *   <li>{@code mapReduceLevels} list has exactly 1 entry</li>
 * </ul>
 */
class MapReduceEnsembleShortCircuitRunTest {

    /** Small item: 40 chars = 10 heuristic tokens (4 chars per token). */
    private static final String SMALL_ITEM = "x".repeat(40);

    /** Large item: 400 chars = 100 heuristic tokens. */
    private static final String LARGE_ITEM = "x".repeat(400);

    /** Exact boundary item: 12 chars = 3 heuristic tokens. */
    private static final String BOUNDARY_ITEM = "x".repeat(12);

    private AtomicInteger agentCounter;

    @BeforeEach
    void setUp() {
        agentCounter = new AtomicInteger(0);
    }

    // ========================
    // Short-circuit fires: estimated <= budget, both factories configured
    // ========================

    @Test
    void run_smallInput_bothDirectFactoriesConfigured_shortCircuitFires() {
        // 3 items of 40 chars each -> 10 tokens each -> 30 total
        // Budget = 100 -> 30 <= 100 -> short-circuit fires
        // Expects exactly 1 LLM call (direct task only)
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct result for all items");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agent("M-" + item.length(), llm))
                .mapTask((item, a) -> task("map " + item.length(), a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Head Chef", llm))
                .directTask((a, items) -> task("Direct: handle all " + items.size() + " items", a))
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Direct result for all items");
        assertThat(output.getTaskOutputs()).hasSize(1);
    }

    @Test
    void run_shortCircuitFires_traceHasCorrectWorkflowAndNodeType() {
        // 3 small items -> 30 tokens <= budget(100) -> short-circuit fires
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct output");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct task", a))
                .targetTokenBudget(100)
                .captureMode(CaptureMode.STANDARD)
                .build()
                .run();

        ExecutionTrace trace = output.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");
    }

    @Test
    void run_shortCircuitFires_singleTaskTraceWithNodeTypeDirect() {
        // 3 small items -> short-circuit fires -> single task trace
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct output");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct task", a))
                .targetTokenBudget(100)
                .captureMode(CaptureMode.STANDARD)
                .build()
                .run();

        ExecutionTrace trace = output.getTrace();
        List<TaskTrace> taskTraces = trace.getTaskTraces();
        assertThat(taskTraces).hasSize(1);
        assertThat(taskTraces.get(0).getNodeType()).isEqualTo(MapReduceEnsemble.NODE_TYPE_DIRECT);
        assertThat(taskTraces.get(0).getMapReduceLevel()).isEqualTo(0);
    }

    @Test
    void run_shortCircuitFires_mapReduceLevelsHasExactlyOneEntry() {
        // 3 small items -> short-circuit fires -> single level summary
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct output");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct task", a))
                .targetTokenBudget(100)
                .build()
                .run();

        ExecutionTrace trace = output.getTrace();
        List<MapReduceLevelSummary> levels = trace.getMapReduceLevels();
        assertThat(levels).hasSize(1);
        assertThat(levels.get(0).getLevel()).isEqualTo(0);
        assertThat(levels.get(0).getTaskCount()).isEqualTo(1);
    }

    // ========================
    // Short-circuit does NOT fire: estimated > budget
    // ========================

    @Test
    void run_largeInput_exceedsBudget_directFactoriesConfigured_normalMapReduceRuns() {
        // 3 items of 400 chars each -> 100 tokens each -> 300 total
        // Budget = 200 -> 300 > 200 -> short-circuit does NOT fire -> normal map-reduce
        // Normal run: 3 map tasks + 1 final reduce
        SequenceResponseChatModel llm =
                new SequenceResponseChatModel("map result 1", "map result 2", "map result 3", "final reduce result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(LARGE_ITEM, LARGE_ITEM, LARGE_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map " + item.length(), a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", new NoOpChatModel()))
                .directTask((a, items) -> task("direct", a))
                .targetTokenBudget(200)
                .build()
                .run();

        // Normal map-reduce ran: final output is the reduce result
        assertThat(output.getRaw()).isEqualTo("final reduce result");

        // taskOutputs from normal map-reduce (3 map + 1 final = 4)
        assertThat(output.getTaskOutputs()).hasSize(4);
    }

    @Test
    void run_largeInput_exceedsBudget_traceHasMapReduceNodeTypes() {
        // 3 large items -> 300 tokens > budget(200) -> normal map-reduce
        SequenceResponseChatModel llm =
                new SequenceResponseChatModel("map result 1", "map result 2", "map result 3", "final result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(LARGE_ITEM, LARGE_ITEM, LARGE_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", new NoOpChatModel()))
                .directTask((a, items) -> task("direct", a))
                .targetTokenBudget(200)
                .captureMode(CaptureMode.STANDARD)
                .build()
                .run();

        ExecutionTrace trace = output.getTrace();
        // Normal adaptive: map level (0) + final reduce level (1)
        assertThat(trace.getMapReduceLevels()).hasSize(2);

        List<TaskTrace> taskTraces = trace.getTaskTraces();
        long mapCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_MAP.equals(t.getNodeType()))
                .count();
        long finalCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE.equals(t.getNodeType()))
                .count();
        long directCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_DIRECT.equals(t.getNodeType()))
                .count();
        assertThat(mapCount).isEqualTo(3);
        assertThat(finalCount).isEqualTo(1);
        assertThat(directCount).isEqualTo(0);
    }

    // ========================
    // Short-circuit does NOT fire: no direct factories configured
    // ========================

    @Test
    void run_smallInput_noDirectFactoriesConfigured_normalMapReduceRuns() {
        // 3 small items -> 30 tokens <= budget(100) BUT no direct factories
        // Short-circuit does NOT fire; normal map-reduce runs
        SequenceResponseChatModel llm = new SequenceResponseChatModel("map A", "map B", "map C", "final result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                // No directAgent/directTask configured
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("final result");

        ExecutionTrace trace = output.getTrace();
        List<TaskTrace> taskTraces = trace.getTaskTraces();
        // map (3) + final-reduce (1) = 4
        assertThat(taskTraces).hasSize(4);
        assertThat(taskTraces.stream()
                        .filter(t -> MapReduceEnsemble.NODE_TYPE_MAP.equals(t.getNodeType()))
                        .count())
                .isEqualTo(3);
        assertThat(taskTraces.stream()
                        .filter(t -> MapReduceEnsemble.NODE_TYPE_DIRECT.equals(t.getNodeType()))
                        .count())
                .isEqualTo(0);
    }

    // ========================
    // N=1 item
    // ========================

    @Test
    void run_singleItem_fitsInBudget_directFactoriesConfigured_shortCircuitFires() {
        // 1 item of 40 chars -> 10 tokens <= budget(100) -> short-circuit fires
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Single item direct result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct", a))
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Single item direct result");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTrace().getTaskTraces()).hasSize(1);
        assertThat(output.getTrace().getTaskTraces().get(0).getNodeType())
                .isEqualTo(MapReduceEnsemble.NODE_TYPE_DIRECT);
    }

    @Test
    void run_singleItem_exceedsBudget_directFactoriesConfigured_normalMapReduceRuns() {
        // 1 item of 400 chars -> 100 tokens > budget(50) -> normal map-reduce
        SequenceResponseChatModel llm = new SequenceResponseChatModel("map result", "final result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(LARGE_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", new NoOpChatModel()))
                .directTask((a, items) -> task("direct", a))
                .targetTokenBudget(50)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("final result");
        // Normal map-reduce: 1 map + 1 final reduce = 2 task traces
        assertThat(output.getTrace().getTaskTraces()).hasSize(2);
        assertThat(output.getTrace().getTaskTraces().stream()
                        .filter(t -> MapReduceEnsemble.NODE_TYPE_DIRECT.equals(t.getNodeType()))
                        .count())
                .isEqualTo(0);
    }

    // ========================
    // All items with empty text representation (via inputEstimator)
    // ========================

    @Test
    void run_allItemsEmptyTextRepresentation_estimatedZeroTokens_shortCircuitFires() {
        // inputEstimator returns "" for every item -> 0 tokens each -> 0 total <= any budget
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct result - empty estimates");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C"))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct", a))
                .inputEstimator(item -> "") // empty text -> 0 tokens
                .targetTokenBudget(10)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Direct result - empty estimates");
        assertThat(output.getTaskOutputs()).hasSize(1);
    }

    // ========================
    // Custom inputEstimator
    // ========================

    @Test
    void run_customInputEstimator_usedInsteadOfToString() {
        // Item toString() would be 400 chars (100 tokens) per item -> 300 total > budget(200)
        // BUT inputEstimator returns a compact 10-char string -> 2 tokens each -> 6 total <= 200
        // -> short-circuit fires
        LargeItem item1 = new LargeItem("A", LARGE_ITEM);
        LargeItem item2 = new LargeItem("B", LARGE_ITEM);
        LargeItem item3 = new LargeItem("C", LARGE_ITEM);

        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct result with compact estimator");

        EnsembleOutput output = MapReduceEnsemble.<LargeItem>builder()
                .items(List.of(item1, item2, item3))
                .mapAgent(item -> agent("M-" + item.getId(), llm))
                .mapTask((item, a) -> task("map " + item.getId(), a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct", a))
                .inputEstimator(LargeItem::getId) // compact: "A", "B", "C" -> 0 tokens each
                .targetTokenBudget(200)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Direct result with compact estimator");
        assertThat(output.getTaskOutputs()).hasSize(1);
    }

    @Test
    void run_noCustomInputEstimator_defaultsToToString() {
        // Large items: toString() returns 400-char string -> 100 tokens each -> 300 total
        // Budget = 200 -> 300 > 200 -> short-circuit does NOT fire
        LargeItem item1 = new LargeItem("A", LARGE_ITEM);
        LargeItem item2 = new LargeItem("B", LARGE_ITEM);
        LargeItem item3 = new LargeItem("C", LARGE_ITEM);

        SequenceResponseChatModel llm = new SequenceResponseChatModel("map A", "map B", "map C", "final result");

        EnsembleOutput output = MapReduceEnsemble.<LargeItem>builder()
                .items(List.of(item1, item2, item3))
                .mapAgent(item -> agent("M-" + item.getId(), llm))
                .mapTask((item, a) -> task("map " + item.getId(), a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", new NoOpChatModel()))
                .directTask((a, items) -> task("direct", a))
                // No inputEstimator: defaults to toString() which returns 400-char string
                .targetTokenBudget(200)
                .build()
                .run();

        // Short-circuit did NOT fire; normal map-reduce ran
        assertThat(output.getRaw()).isEqualTo("final result");
        assertThat(output.getTaskOutputs()).hasSize(4); // 3 map + 1 final reduce
    }

    // ========================
    // Boundary: estimated exactly equals budget -> short-circuit fires (inclusive)
    // ========================

    @Test
    void run_estimatedTokensExactlyEqualsBudget_shortCircuitFires() {
        // 3 items of 12 chars each -> 3 tokens each (12/4=3) -> 9 total
        // Budget = 9 -> 9 <= 9 -> short-circuit fires (boundary is inclusive)
        SequenceResponseChatModel llm = new SequenceResponseChatModel("Boundary direct result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(BOUNDARY_ITEM, BOUNDARY_ITEM, BOUNDARY_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct", a))
                .targetTokenBudget(9) // exactly 9 = estimated
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("Boundary direct result");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTrace().getTaskTraces().get(0).getNodeType())
                .isEqualTo(MapReduceEnsemble.NODE_TYPE_DIRECT);
    }

    @Test
    void run_estimatedTokensOneOverBudget_shortCircuitDoesNotFire() {
        // 3 items of 12 chars each -> 3 tokens each -> 9 total
        // Budget = 8 -> 9 > 8 -> short-circuit does NOT fire
        SequenceResponseChatModel llm = new SequenceResponseChatModel("map 1", "map 2", "map 3", "reduce result");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(BOUNDARY_ITEM, BOUNDARY_ITEM, BOUNDARY_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", new NoOpChatModel()))
                .directTask((a, items) -> task("direct", a))
                .targetTokenBudget(8) // one less than estimated (9)
                .build()
                .run();

        // Normal map-reduce ran
        assertThat(output.getRaw()).isEqualTo("reduce result");
        assertThat(output.getTrace().getTaskTraces().stream()
                        .filter(t -> MapReduceEnsemble.NODE_TYPE_DIRECT.equals(t.getNodeType()))
                        .count())
                .isEqualTo(0);
    }

    // ========================
    // Direct task receives complete List<T>
    // ========================

    @Test
    void run_shortCircuitFires_directTaskReceivesAllItems() {
        List<String> inputItems = List.of(SMALL_ITEM, SMALL_ITEM, SMALL_ITEM);

        // Capture what items were actually passed to the directTask factory
        AtomicReference<List<String>> capturedItems = new AtomicReference<>();

        SequenceResponseChatModel llm = new SequenceResponseChatModel("Direct output");

        MapReduceEnsemble.<String>builder()
                .items(inputItems)
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> {
                    capturedItems.set(new ArrayList<>(items));
                    return task("direct: " + items.size() + " items", a);
                })
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(capturedItems.get()).isNotNull();
        assertThat(capturedItems.get()).hasSize(3);
        assertThat(capturedItems.get()).containsExactlyElementsOf(inputItems);
    }

    // ========================
    // EnsembleOutput.getRaw() equals direct task output
    // ========================

    @Test
    void run_shortCircuitFires_rawOutputIsDirectTaskOutput() {
        String expectedDirectOutput = "This is the direct task output for all items combined";
        SequenceResponseChatModel llm = new SequenceResponseChatModel(expectedDirectOutput);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of(SMALL_ITEM, SMALL_ITEM))
                .mapAgent(item -> agent("M", llm))
                .mapTask((item, a) -> task("map", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .directAgent(() -> agent("Direct Agent", llm))
                .directTask((a, items) -> task("direct task", a))
                .targetTokenBudget(100)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo(expectedDirectOutput);
    }

    // ========================
    // Helpers
    // ========================

    private Agent agent(String role, ChatModel llm) {
        return Agent.builder().role(role).goal("goal").llm(llm).build();
    }

    private Task task(String description, Agent agent) {
        return Task.builder()
                .description(description)
                .expectedOutput("output")
                .agent(agent)
                .build();
    }

    private Task reduceTask(Agent agent, List<Task> chunk) {
        return Task.builder()
                .description("reduce")
                .expectedOutput("reduced")
                .agent(agent)
                .context(chunk)
                .build();
    }

    // ========================
    // Helper domain class
    // ========================

    /**
     * A domain object whose {@code toString()} returns a long string (used to test
     * that the default inputEstimator falls back to {@code toString()}).
     */
    static class LargeItem {
        private final String id;
        private final String largeContent;

        LargeItem(String id, String largeContent) {
            this.id = id;
            this.largeContent = largeContent;
        }

        String getId() {
            return id;
        }

        @Override
        public String toString() {
            return largeContent; // returns the 400-char string
        }
    }

    // ========================
    // Mock ChatModel implementations
    // ========================

    /**
     * Returns responses from a pre-configured sequence, cycling when exhausted.
     */
    static class SequenceResponseChatModel implements ChatModel {
        private final String[] responses;
        private final AtomicInteger index = new AtomicInteger(0);

        SequenceResponseChatModel(String... responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            int i = index.getAndIncrement() % responses.length;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses[i]))
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    /**
     * No-op model that throws -- used where the LLM must not be invoked.
     */
    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new AssertionError(
                    "NoOpChatModel was unexpectedly called -- " + "short-circuit should have prevented this LLM call");
        }
    }
}
