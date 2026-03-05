package net.agentensemble.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/** Unit tests for ExecutionMetrics.from() aggregation and EMPTY constant. */
class ExecutionMetricsTest {

    @Test
    void testEmpty_allTokensUnknown() {
        assertThat(ExecutionMetrics.EMPTY.getTotalInputTokens()).isEqualTo(-1L);
        assertThat(ExecutionMetrics.EMPTY.getTotalOutputTokens()).isEqualTo(-1L);
        assertThat(ExecutionMetrics.EMPTY.getTotalTokens()).isEqualTo(-1L);
    }

    @Test
    void testEmpty_allDurationsZero() {
        assertThat(ExecutionMetrics.EMPTY.getTotalLlmLatency()).isEqualTo(Duration.ZERO);
        assertThat(ExecutionMetrics.EMPTY.getTotalToolExecutionTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    void testFrom_nullOutputs_returnsEmpty() {
        assertThat(ExecutionMetrics.from(null)).isEqualTo(ExecutionMetrics.EMPTY);
    }

    @Test
    void testFrom_emptyOutputs_returnsEmpty() {
        assertThat(ExecutionMetrics.from(List.of())).isEqualTo(ExecutionMetrics.EMPTY);
    }

    @Test
    void testFrom_singleOutputWithKnownTokens_aggregatesCorrectly() {
        TaskMetrics tm = TaskMetrics.builder()
                .inputTokens(1000L)
                .outputTokens(500L)
                .totalTokens(1500L)
                .llmLatency(Duration.ofSeconds(2))
                .toolExecutionTime(Duration.ofMillis(300))
                .llmCallCount(2)
                .toolCallCount(3)
                .build();

        TaskOutput output = taskOutput("Researcher", tm);
        ExecutionMetrics metrics = ExecutionMetrics.from(List.of(output));

        assertThat(metrics.getTotalInputTokens()).isEqualTo(1000L);
        assertThat(metrics.getTotalOutputTokens()).isEqualTo(500L);
        assertThat(metrics.getTotalTokens()).isEqualTo(1500L);
        assertThat(metrics.getTotalLlmLatency()).isEqualTo(Duration.ofSeconds(2));
        assertThat(metrics.getTotalToolExecutionTime()).isEqualTo(Duration.ofMillis(300));
        assertThat(metrics.getTotalLlmCallCount()).isEqualTo(2);
        assertThat(metrics.getTotalToolCalls()).isEqualTo(3);
    }

    @Test
    void testFrom_multipleOutputs_sumsTokens() {
        TaskMetrics tm1 = TaskMetrics.builder()
                .inputTokens(500L)
                .outputTokens(200L)
                .totalTokens(700L)
                .llmLatency(Duration.ofSeconds(1))
                .build();
        TaskMetrics tm2 = TaskMetrics.builder()
                .inputTokens(800L)
                .outputTokens(300L)
                .totalTokens(1100L)
                .llmLatency(Duration.ofSeconds(2))
                .build();

        ExecutionMetrics metrics = ExecutionMetrics.from(List.of(taskOutput("Agent1", tm1), taskOutput("Agent2", tm2)));

        assertThat(metrics.getTotalInputTokens()).isEqualTo(1300L);
        assertThat(metrics.getTotalOutputTokens()).isEqualTo(500L);
        assertThat(metrics.getTotalTokens()).isEqualTo(1800L);
        assertThat(metrics.getTotalLlmLatency()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void testFrom_anyUnknownInput_propagatesNegativeOne() {
        TaskMetrics knownInput = TaskMetrics.builder()
                .inputTokens(500L)
                .outputTokens(200L)
                .totalTokens(700L)
                .build();
        TaskMetrics unknownInput = TaskMetrics.EMPTY; // inputTokens=-1

        ExecutionMetrics metrics =
                ExecutionMetrics.from(List.of(taskOutput("Agent1", knownInput), taskOutput("Agent2", unknownInput)));

        assertThat(metrics.getTotalInputTokens()).isEqualTo(-1L);
        assertThat(metrics.getTotalTokens()).isEqualTo(-1L);
        // Output also unknown because EMPTY has -1
        assertThat(metrics.getTotalOutputTokens()).isEqualTo(-1L);
    }

    @Test
    void testFrom_costEstimates_areAggregated() {
        CostEstimate cost1 = CostEstimate.builder()
                .inputCost(new java.math.BigDecimal("0.001"))
                .outputCost(new java.math.BigDecimal("0.002"))
                .totalCost(new java.math.BigDecimal("0.003"))
                .build();
        CostEstimate cost2 = CostEstimate.builder()
                .inputCost(new java.math.BigDecimal("0.002"))
                .outputCost(new java.math.BigDecimal("0.003"))
                .totalCost(new java.math.BigDecimal("0.005"))
                .build();

        TaskMetrics tm1 = TaskMetrics.builder().costEstimate(cost1).build();
        TaskMetrics tm2 = TaskMetrics.builder().costEstimate(cost2).build();

        ExecutionMetrics metrics = ExecutionMetrics.from(List.of(taskOutput("Agent1", tm1), taskOutput("Agent2", tm2)));

        assertThat(metrics.getTotalCostEstimate()).isNotNull();
        assertThat(metrics.getTotalCostEstimate().getTotalCost())
                .isEqualByComparingTo(new java.math.BigDecimal("0.008"));
    }

    @Test
    void testFrom_noCostEstimate_remainsNull() {
        TaskMetrics tm =
                TaskMetrics.builder().inputTokens(100L).outputTokens(50L).build();
        ExecutionMetrics metrics = ExecutionMetrics.from(List.of(taskOutput("Agent1", tm)));

        assertThat(metrics.getTotalCostEstimate()).isNull();
    }

    private static TaskOutput taskOutput(String agentRole, TaskMetrics metrics) {
        TaskOutput output = mock(TaskOutput.class);
        when(output.getAgentRole()).thenReturn(agentRole);
        when(output.getMetrics()).thenReturn(metrics);
        return output;
    }
}
