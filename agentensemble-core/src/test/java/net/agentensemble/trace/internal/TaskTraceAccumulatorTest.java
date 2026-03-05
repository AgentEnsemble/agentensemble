package net.agentensemble.trace.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import net.agentensemble.metrics.TaskMetrics;
import net.agentensemble.trace.LlmResponseType;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.trace.ToolCallOutcome;
import net.agentensemble.trace.ToolCallTrace;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for TaskTraceAccumulator covering LLM call recording,
 * tool call accumulation, token aggregation, and trace building.
 */
class TaskTraceAccumulatorTest {

    private static final Instant STARTED_AT = Instant.parse("2026-03-05T09:00:00Z");

    private TaskTraceAccumulator newAccumulator() {
        return new TaskTraceAccumulator("Researcher", "Research AI agents", "Expected output", STARTED_AT);
    }

    @Test
    void testBuildTrace_noLlmCalls_emptyInteractions() {
        TaskTraceAccumulator acc = newAccumulator();
        Instant completedAt = STARTED_AT.plusSeconds(1);
        TaskTrace trace = acc.buildTrace("response", null, completedAt, null);

        assertThat(trace.getLlmInteractions()).isEmpty();
        assertThat(trace.getAgentRole()).isEqualTo("Researcher");
        assertThat(trace.getTaskDescription()).isEqualTo("Research AI agents");
        assertThat(trace.getFinalOutput()).isEqualTo("response");
        assertThat(trace.getMetrics()).isNotNull();
    }

    @Test
    void testRecordPrompts_capturedInTrace() {
        TaskTraceAccumulator acc = newAccumulator();
        acc.recordPrompts("System prompt", "User prompt", Duration.ofMillis(50));
        TaskTrace trace = acc.buildTrace("output", null, STARTED_AT.plusSeconds(1), null);

        assertThat(trace.getPrompts()).isNotNull();
        assertThat(trace.getPrompts().getSystemPrompt()).isEqualTo("System prompt");
        assertThat(trace.getPrompts().getUserPrompt()).isEqualTo("User prompt");
        assertThat(trace.getMetrics().getPromptBuildTime()).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void testSingleLlmCall_withKnownTokens_recordedInInteractionAndMetrics() {
        TaskTraceAccumulator acc = newAccumulator();
        Instant callStart = STARTED_AT.plusSeconds(1);
        Instant callEnd = callStart.plusSeconds(2);

        TokenUsage tokenUsage = mock(TokenUsage.class);
        when(tokenUsage.inputTokenCount()).thenReturn(800);
        when(tokenUsage.outputTokenCount()).thenReturn(400);

        acc.beginLlmCall(callStart);
        acc.endLlmCall(callEnd, tokenUsage);
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "Final answer");

        TaskTrace trace = acc.buildTrace("Final answer", null, callEnd, null);
        TaskMetrics metrics = trace.getMetrics();

        assertThat(trace.getLlmInteractions()).hasSize(1);
        assertThat(trace.getLlmInteractions().get(0).getInputTokens()).isEqualTo(800L);
        assertThat(trace.getLlmInteractions().get(0).getOutputTokens()).isEqualTo(400L);
        assertThat(trace.getLlmInteractions().get(0).getResponseType()).isEqualTo(LlmResponseType.FINAL_ANSWER);
        assertThat(trace.getLlmInteractions().get(0).getResponseText()).isEqualTo("Final answer");
        assertThat(trace.getLlmInteractions().get(0).getToolCalls()).isEmpty();

        assertThat(metrics.getInputTokens()).isEqualTo(800L);
        assertThat(metrics.getOutputTokens()).isEqualTo(400L);
        assertThat(metrics.getTotalTokens()).isEqualTo(1200L);
        assertThat(metrics.getLlmCallCount()).isEqualTo(1);
        assertThat(metrics.getLlmLatency()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void testSingleLlmCall_withNullTokenUsage_tokensUnknown() {
        TaskTraceAccumulator acc = newAccumulator();
        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null); // null = provider didn't return usage
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "response");

        TaskMetrics metrics = acc.buildMetrics(null);

        assertThat(metrics.getInputTokens()).isEqualTo(-1L);
        assertThat(metrics.getOutputTokens()).isEqualTo(-1L);
        assertThat(metrics.getTotalTokens()).isEqualTo(-1L);
    }

    @Test
    void testToolCallAccumulation_recordedInIterationAndMetrics() {
        TaskTraceAccumulator acc = newAccumulator();
        Instant callStart = STARTED_AT;
        Instant callEnd = callStart.plusSeconds(1);

        acc.beginLlmCall(callStart);
        acc.endLlmCall(callEnd, null);

        // Add a tool call to current iteration
        ToolCallTrace toolTrace = ToolCallTrace.builder()
                .toolName("web_search")
                .arguments("{\"query\":\"AI agents\"}")
                .result("Search results...")
                .startedAt(callEnd)
                .completedAt(callEnd.plusMillis(300))
                .duration(Duration.ofMillis(300))
                .outcome(ToolCallOutcome.SUCCESS)
                .build();
        acc.addToolCallToCurrentIteration(toolTrace);

        acc.finalizeIteration(LlmResponseType.TOOL_CALLS, null);

        // Second iteration - final answer
        Instant call2Start = callEnd.plusMillis(500);
        Instant call2End = call2Start.plusSeconds(1);
        acc.beginLlmCall(call2Start);
        acc.endLlmCall(call2End, null);
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "Final answer");

        TaskTrace trace = acc.buildTrace("Final answer", null, call2End, null);
        TaskMetrics metrics = trace.getMetrics();

        assertThat(trace.getLlmInteractions()).hasSize(2);
        assertThat(trace.getLlmInteractions().get(0).getToolCalls()).hasSize(1);
        assertThat(trace.getLlmInteractions().get(0).getToolCalls().get(0).getToolName())
                .isEqualTo("web_search");
        assertThat(trace.getLlmInteractions().get(1).getToolCalls()).isEmpty();

        assertThat(metrics.getToolCallCount()).isEqualTo(1);
        assertThat(metrics.getToolExecutionTime()).isEqualTo(Duration.ofMillis(300));
        assertThat(metrics.getLlmCallCount()).isEqualTo(2);
    }

    @Test
    void testMultipleLlmCalls_tokensMixedKnownAndUnknown_propagatesUnknown() {
        TaskTraceAccumulator acc = newAccumulator();

        // First call: known tokens
        TokenUsage tokenUsage = mock(TokenUsage.class);
        when(tokenUsage.inputTokenCount()).thenReturn(500);
        when(tokenUsage.outputTokenCount()).thenReturn(200);
        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), tokenUsage);
        acc.finalizeIteration(LlmResponseType.TOOL_CALLS, null);

        // Second call: unknown tokens
        acc.beginLlmCall(STARTED_AT.plusSeconds(2));
        acc.endLlmCall(STARTED_AT.plusSeconds(3), null);
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "done");

        TaskMetrics metrics = acc.buildMetrics(null);

        // Once any call has unknown tokens, the aggregate is -1
        assertThat(metrics.getInputTokens()).isEqualTo(-1L);
        assertThat(metrics.getTotalTokens()).isEqualTo(-1L);
    }

    @Test
    void testBuildMetrics_withCostConfiguration_computesCost() {
        TaskTraceAccumulator acc = newAccumulator();

        TokenUsage tokenUsage = mock(TokenUsage.class);
        when(tokenUsage.inputTokenCount()).thenReturn(1000);
        when(tokenUsage.outputTokenCount()).thenReturn(500);
        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), tokenUsage);
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "done");

        net.agentensemble.metrics.CostConfiguration costConfig = net.agentensemble.metrics.CostConfiguration.builder()
                .inputTokenRate(new java.math.BigDecimal("0.001"))
                .outputTokenRate(new java.math.BigDecimal("0.002"))
                .build();

        TaskMetrics metrics = acc.buildMetrics(costConfig);

        assertThat(metrics.getCostEstimate()).isNotNull();
        assertThat(metrics.getCostEstimate().getInputCost())
                .isEqualByComparingTo(new java.math.BigDecimal("1.00000000"));
        assertThat(metrics.getCostEstimate().getOutputCost())
                .isEqualByComparingTo(new java.math.BigDecimal("1.00000000"));
    }

    @Test
    void testBuildTrace_iterationIndex_isZeroBased() {
        TaskTraceAccumulator acc = newAccumulator();

        acc.beginLlmCall(STARTED_AT);
        acc.endLlmCall(STARTED_AT.plusSeconds(1), null);
        acc.finalizeIteration(LlmResponseType.TOOL_CALLS, null);

        acc.beginLlmCall(STARTED_AT.plusSeconds(2));
        acc.endLlmCall(STARTED_AT.plusSeconds(3), null);
        acc.finalizeIteration(LlmResponseType.FINAL_ANSWER, "done");

        TaskTrace trace = acc.buildTrace("done", null, STARTED_AT.plusSeconds(4), null);

        assertThat(trace.getLlmInteractions().get(0).getIterationIndex()).isEqualTo(0);
        assertThat(trace.getLlmInteractions().get(1).getIterationIndex()).isEqualTo(1);
    }
}
