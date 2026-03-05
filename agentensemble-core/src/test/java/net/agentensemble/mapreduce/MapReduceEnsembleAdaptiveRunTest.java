package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.MapReduceLevelSummary;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.workflow.ParallelErrorStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for adaptive {@link MapReduceEnsemble} execution.
 *
 * <p>Tests use {@link SequenceResponseChatModel} to control LLM responses without real
 * network calls. Token estimation is driven by the heuristic fallback ({@code length / 4})
 * since the mock returns {@code outputTokens = -1} (unknown).
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>6 items whose outputs fit in budget: 1 map + 1 final reduce (2 ensemble runs)</li>
 *   <li>6 items whose outputs exceed budget: 1 map + intermediate reduces + 1 final</li>
 *   <li>maxReduceLevels=1 with outputs still over budget: WARN logged, final reduce runs</li>
 *   <li>FAIL_FAST: one map task fails -> {@code TaskExecutionException} before reduce</li>
 *   <li>CONTINUE_ON_ERROR: 2 of 6 map tasks fail -> 4 surviving outputs proceed to reduce</li>
 *   <li>CostConfiguration: total cost estimate is sum of all levels</li>
 *   <li>CaptureMode.STANDARD: LlmInteractions present in aggregated trace</li>
 * </ul>
 */
class MapReduceEnsembleAdaptiveRunTest {

    /**
     * Heuristic: 4 chars per token. A 400-char output = 100 tokens.
     * A 100-char output = 25 tokens.
     */
    private static final int CHARS_PER_TOKEN = 4;

    /** Long map-output: 400 chars = 100 heuristic tokens each. */
    private static final String LONG_OUTPUT = "x".repeat(400);

    /** Short reduce-output: 100 chars = 25 heuristic tokens each. */
    private static final String SHORT_OUTPUT = "y".repeat(100);

    private AtomicInteger agentCounter;

    @BeforeEach
    void setUp() {
        agentCounter = new AtomicInteger(0);
    }

    // ========================
    // Test: all map outputs fit in budget -> 2 ensemble runs total
    // ========================

    @Test
    void run_sixItems_outputsFitInBudget_singleFinalReduce() {
        // 6 map outputs at 25 tokens each = 150 total
        // Budget = 200 -> 150 <= 200 -> single final reduce
        // LLM calls: 6 map + 1 final reduce = 7 total
        // Heuristic: SHORT_OUTPUT (100 chars) = 25 tokens
        SequenceResponseChatModel llm = new SequenceResponseChatModel(
                // 6 map responses (short output)
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                // 1 final reduce response
                "Final plan");

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C", "D", "E", "F"))
                .mapAgent(item -> agent("M-" + item, llm))
                .mapTask((item, a) -> task("map " + item, a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(200)
                .build();

        EnsembleOutput output = mre.run();

        assertThat(output.getRaw()).isEqualTo("Final plan");

        // Verify aggregated trace
        ExecutionTrace trace = output.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");

        // Map level (level 0) + final reduce level (level 1) = 2 levels
        assertThat(trace.getMapReduceLevels()).hasSize(2);
        MapReduceLevelSummary mapLevel = trace.getMapReduceLevels().get(0);
        assertThat(mapLevel.getLevel()).isEqualTo(0);
        assertThat(mapLevel.getTaskCount()).isEqualTo(6);

        MapReduceLevelSummary finalLevel = trace.getMapReduceLevels().get(1);
        assertThat(finalLevel.getLevel()).isEqualTo(1);
        assertThat(finalLevel.getTaskCount()).isEqualTo(1);

        // All 7 task traces present (6 map + 1 final)
        List<TaskTrace> taskTraces = trace.getTaskTraces();
        assertThat(taskTraces).hasSize(7);

        // Verify nodeType annotations
        long mapCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_MAP.equals(t.getNodeType()))
                .count();
        long finalCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE.equals(t.getNodeType()))
                .count();
        assertThat(mapCount).isEqualTo(6);
        assertThat(finalCount).isEqualTo(1);

        // Verify mapReduceLevel annotations
        taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_MAP.equals(t.getNodeType()))
                .forEach(t -> assertThat(t.getMapReduceLevel()).isEqualTo(0));
        taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE.equals(t.getNodeType()))
                .forEach(t -> assertThat(t.getMapReduceLevel()).isEqualTo(1));
    }

    // ========================
    // Test: outputs exceed budget -> intermediate reduce levels
    // ========================

    @Test
    void run_sixItems_outputsExceedBudget_multiLevelReduce() {
        // 6 map outputs at 100 tokens each = 600 total
        // Budget = 250 -> 600 > 250 -> need intermediate reduce
        // Bin-packing: 100+100=200 <= 250 -> 3 bins of 2
        // After L1 reduce: 3 outputs at 25 tokens each = 75 total <= 250 -> final reduce
        // Total: 3 ensemble runs (map + L1-intermediate + final)
        SequenceResponseChatModel llm = new SequenceResponseChatModel(
                // 6 map responses (long output = 100 tokens each)
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                // 3 L1 reduce responses (short output = 25 tokens each)
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                SHORT_OUTPUT,
                // 1 final reduce response
                "Final consolidated plan");

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C", "D", "E", "F"))
                .mapAgent(item -> agent("M-" + item, llm))
                .mapTask((item, a) -> task("map " + item, a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(250)
                .build();

        EnsembleOutput output = mre.run();

        assertThat(output.getRaw()).isEqualTo("Final consolidated plan");

        ExecutionTrace trace = output.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");

        // 3 levels: map(0) + intermediate-reduce(1) + final-reduce(2)
        assertThat(trace.getMapReduceLevels()).hasSize(3);

        List<TaskTrace> taskTraces = trace.getTaskTraces();
        // 6 map + 3 reduce + 1 final = 10 task traces
        assertThat(taskTraces).hasSize(10);

        long mapCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_MAP.equals(t.getNodeType()))
                .count();
        long reduceCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_REDUCE.equals(t.getNodeType()))
                .count();
        long finalCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE.equals(t.getNodeType()))
                .count();

        assertThat(mapCount).isEqualTo(6);
        assertThat(reduceCount).isEqualTo(3);
        assertThat(finalCount).isEqualTo(1);
    }

    // ========================
    // Test: maxReduceLevels=1 with still-high outputs -> WARN, final reduce runs
    // ========================

    @Test
    void run_maxReduceLevelsReached_proceedsWithFinalReduce() {
        // 6 map outputs at 100 tokens each = 600 > budget(250)
        // maxReduceLevels=1: bin-packing creates 3 L1 reduce tasks
        // L1 reduce outputs = 100 tokens each = 300 total > budget(250)
        // maxReduceLevels reached -> proceed with final reduce anyway
        SequenceResponseChatModel llm = new SequenceResponseChatModel(
                // 6 map responses (long output)
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                // 3 L1 reduce responses (still long = 100 tokens)
                LONG_OUTPUT,
                LONG_OUTPUT,
                LONG_OUTPUT,
                // 1 final reduce response
                "Final with exceeded budget");

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C", "D", "E", "F"))
                .mapAgent(item -> agent("M-" + item, llm))
                .mapTask((item, a) -> task("map " + item, a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(250)
                .maxReduceLevels(1)
                .build();

        // Should succeed (not throw) even though budget is still exceeded
        EnsembleOutput output = mre.run();
        assertThat(output.getRaw()).isEqualTo("Final with exceeded budget");

        // 3 levels: map + L1-reduce + final-reduce
        ExecutionTrace trace = output.getTrace();
        assertThat(trace.getMapReduceLevels()).hasSize(3);
    }

    // ========================
    // Test: FAIL_FAST - one map task fails
    // ========================

    @Test
    void run_mapTaskFails_failFast_throwsTaskExecutionException() {
        // Map task 3 will fail (throw exception)
        FailOnCallChatModel failingLlm = new FailOnCallChatModel(3);

        assertThatThrownBy(() -> MapReduceEnsemble.<String>builder()
                        .items(List.of("A", "B", "C", "D"))
                        .mapAgent(item -> agent("M-" + item, failingLlm))
                        .mapTask((item, a) -> task("map " + item, a))
                        .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), failingLlm))
                        .reduceTask((a, chunk) -> reduceTask(a, chunk))
                        .targetTokenBudget(8_000)
                        .parallelErrorStrategy(ParallelErrorStrategy.FAIL_FAST)
                        .build()
                        .run())
                .isInstanceOf(TaskExecutionException.class);
    }

    // ========================
    // Test: CONTINUE_ON_ERROR - surviving outputs proceed to reduce
    // ========================

    @Test
    void run_twoMapTasksFail_continueOnError_survivingOutputsProceedToReduce() {
        // 6 items: items 3 and 5 fail (by call order), 4 survive.
        // Budget large enough that all 4 survivors fit -> single final reduce.
        //
        // Note: when ParallelExecutionException is thrown (CONTINUE_ON_ERROR partial failure),
        // the Ensemble does not produce a full ExecutionTrace for the map phase. The survivor
        // outputs are extracted from the exception's completedTaskOutputs. As a result, the
        // aggregated trace has task traces only from the final reduce level (not from the
        // map phase). The taskOutputs list contains 4 map survivors + 1 final reduce = 5 total.
        PartialFailChatModel llm = new PartialFailChatModel(
                /* failOnCalls */ List.of(3, 5),
                /* successResponse */ SHORT_OUTPUT,
                /* failMsg */ "intentional map failure",
                /* reduceResponse */ "reduced output");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C", "D", "E", "F"))
                .mapAgent(item -> agent("M-" + item, llm))
                .mapTask((item, a) -> task("map " + item, a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(1_000) // large budget: all 4 survivors fit
                .parallelErrorStrategy(ParallelErrorStrategy.CONTINUE_ON_ERROR)
                .build()
                .run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isEqualTo("reduced output");

        // Verify 4 survivor + 1 final-reduce outputs in the aggregated taskOutputs list
        assertThat(output.getTaskOutputs()).hasSize(5);

        // Trace is present; final reduce task trace is annotated correctly
        ExecutionTrace trace = output.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getWorkflow()).isEqualTo("MAP_REDUCE_ADAPTIVE");

        // Map phase trace is unavailable (ParallelExecutionException path has no trace).
        // Final reduce trace is present with correct nodeType.
        List<TaskTrace> taskTraces = trace.getTaskTraces();
        long finalReduceCount = taskTraces.stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE.equals(t.getNodeType()))
                .count();
        assertThat(finalReduceCount).isEqualTo(1);
    }

    // ========================
    // Test: CostConfiguration - total cost is sum of all levels
    // ========================

    @Test
    void run_costConfiguration_totalCostIsSumOfAllLevels() {
        // Use a model that reports known token counts so cost is deterministic
        TokenCountingChatModel llm =
                new TokenCountingChatModel(/* inputTokens */ 100, /* outputTokens */ 50, /* response */ SHORT_OUTPUT);
        TokenCountingChatModel reduceLlm =
                new TokenCountingChatModel(/* inputTokens */ 200, /* outputTokens */ 25, /* response */ "reduced");

        // Budget large enough for all 3 items to fit -> 1 map + 1 final reduce
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C"))
                .mapAgent(item -> agent("M-" + item, llm))
                .mapTask((item, a) -> task("map " + item, a))
                .reduceAgent(() -> agent("R-" + agentCounter.incrementAndGet(), reduceLlm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(10_000)
                .costConfiguration(CostConfiguration.builder()
                        .inputTokenRate(new BigDecimal("0.000001"))
                        .outputTokenRate(new BigDecimal("0.000002"))
                        .build())
                .build();

        EnsembleOutput output = mre.run();

        assertThat(output.getMetrics().getTotalCostEstimate()).isNotNull();
        assertThat(output.getMetrics().getTotalCostEstimate().getTotalCost()).isGreaterThan(BigDecimal.ZERO);

        // Verify trace has cost estimate
        ExecutionTrace trace = output.getTrace();
        assertThat(trace.getTotalCostEstimate()).isNotNull();
    }

    // ========================
    // Test: CaptureMode.STANDARD - LlmInteractions are captured
    // ========================

    @Test
    void run_captureMode_standard_llmInteractionsCaptured() {
        SequenceResponseChatModel llm = new SequenceResponseChatModel(
                SHORT_OUTPUT,
                SHORT_OUTPUT, // 2 map
                "final result"); // 1 final reduce

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B"))
                .mapAgent(item -> agent("M-" + item, llm))
                .mapTask((item, a) -> task("map " + item, a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(10_000)
                .captureMode(CaptureMode.STANDARD)
                .build()
                .run();

        ExecutionTrace trace = output.getTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.getCaptureMode()).isEqualTo(CaptureMode.STANDARD);

        // Map task traces should have LLM interactions captured
        List<TaskTrace> mapTraces = trace.getTaskTraces().stream()
                .filter(t -> MapReduceEnsemble.NODE_TYPE_MAP.equals(t.getNodeType()))
                .toList();
        assertThat(mapTraces).hasSize(2);
        mapTraces.forEach(t -> assertThat(t.getLlmInteractions()).isNotEmpty());
    }

    // ========================
    // Test: N=1 single map + single final reduce
    // ========================

    @Test
    void run_singleItem_singleMapAndSingleFinalReduce() {
        SequenceResponseChatModel llm = new SequenceResponseChatModel(SHORT_OUTPUT, "final");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of("A"))
                .mapAgent(item -> agent("M-A", llm))
                .mapTask((item, a) -> task("map A", a))
                .reduceAgent(() -> agent("R", llm))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(500)
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("final");
        ExecutionTrace trace = output.getTrace();
        assertThat(trace.getMapReduceLevels()).hasSize(2); // map + final
        assertThat(trace.getTaskTraces()).hasSize(2); // 1 map + 1 final
    }

    // ========================
    // Test: isAdaptiveMode() returns true
    // ========================

    @Test
    void isAdaptiveMode_returnsTrue() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(List.of("A"))
                .mapAgent(item -> agent("M", new NoOpChatModel()))
                .mapTask((item, a) -> task("map A", a))
                .reduceAgent(() -> agent("R", new NoOpChatModel()))
                .reduceTask((a, chunk) -> reduceTask(a, chunk))
                .targetTokenBudget(8_000)
                .build();

        assertThat(mre.isAdaptiveMode()).isTrue();
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
    // Mock ChatModel implementations
    // ========================

    /**
     * Returns responses from a pre-configured sequence, cycling when exhausted.
     * Token counts are not set ({@code outputTokens = -1}) to force heuristic estimation.
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
                    // No TokenUsage -> outputTokens will be -1 (heuristic fallback)
                    .build();
        }
    }

    /**
     * Fails on the Nth call ({@code failOnCall} is 1-based). All other calls return
     * a short response.
     */
    static class FailOnCallChatModel implements ChatModel {
        private final int failOnCall;
        private final AtomicInteger callCount = new AtomicInteger(0);

        FailOnCallChatModel(int failOnCall) {
            this.failOnCall = failOnCall;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            int n = callCount.incrementAndGet();
            if (n == failOnCall) {
                throw new RuntimeException("Intentional LLM failure on call " + n);
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(SHORT_OUTPUT))
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    /**
     * Fails on specific calls (1-based), succeeds on all others.
     * Uses a different response for reduce tasks (detected by call index after N map calls).
     */
    static class PartialFailChatModel implements ChatModel {
        private final List<Integer> failOnCalls;
        private final String successResponse;
        private final String failMsg;
        private final String reduceResponse;
        private final AtomicInteger callCount = new AtomicInteger(0);

        PartialFailChatModel(List<Integer> failOnCalls, String successResponse, String failMsg, String reduceResponse) {
            this.failOnCalls = failOnCalls;
            this.successResponse = successResponse;
            this.failMsg = failMsg;
            this.reduceResponse = reduceResponse;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            int n = callCount.incrementAndGet();
            if (failOnCalls.contains(n)) {
                throw new RuntimeException(failMsg);
            }
            // After the map phase, return reduce response
            String response = n > 6 ? reduceResponse : successResponse;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .finishReason(FinishReason.STOP)
                    .build();
        }
    }

    /**
     * Always returns a fixed response with known token counts.
     */
    static class TokenCountingChatModel implements ChatModel {
        private final int inputTokens;
        private final int outputTokens;
        private final String response;

        TokenCountingChatModel(int inputTokens, int outputTokens, String response) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.response = response;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .finishReason(FinishReason.STOP)
                    .tokenUsage(new TokenUsage(inputTokens, outputTokens, inputTokens + outputTokens))
                    .build();
        }
    }

    /**
     * No-op model that throws - used only for build-time tests where run() is not called.
     */
    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called");
        }
    }
}
