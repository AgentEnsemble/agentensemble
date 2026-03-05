package net.agentensemble.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for TaskMetrics defaults, EMPTY constant, and builder. */
class TaskMetricsTest {

    @Test
    void testEmpty_allTokensUnknown() {
        assertThat(TaskMetrics.EMPTY.getInputTokens()).isEqualTo(-1L);
        assertThat(TaskMetrics.EMPTY.getOutputTokens()).isEqualTo(-1L);
        assertThat(TaskMetrics.EMPTY.getTotalTokens()).isEqualTo(-1L);
    }

    @Test
    void testEmpty_allDurationsZero() {
        assertThat(TaskMetrics.EMPTY.getLlmLatency()).isEqualTo(Duration.ZERO);
        assertThat(TaskMetrics.EMPTY.getToolExecutionTime()).isEqualTo(Duration.ZERO);
        assertThat(TaskMetrics.EMPTY.getMemoryRetrievalTime()).isEqualTo(Duration.ZERO);
        assertThat(TaskMetrics.EMPTY.getPromptBuildTime()).isEqualTo(Duration.ZERO);
    }

    @Test
    void testEmpty_allCountsZero() {
        assertThat(TaskMetrics.EMPTY.getLlmCallCount()).isZero();
        assertThat(TaskMetrics.EMPTY.getToolCallCount()).isZero();
        assertThat(TaskMetrics.EMPTY.getDelegationCount()).isZero();
    }

    @Test
    void testEmpty_noCostEstimate() {
        assertThat(TaskMetrics.EMPTY.getCostEstimate()).isNull();
    }

    @Test
    void testEmpty_memoryOperationsZero() {
        assertThat(TaskMetrics.EMPTY.getMemoryOperations()).isEqualTo(MemoryOperationCounts.ZERO);
    }

    @Test
    void testBuilder_populatesFields() {
        Duration llmLatency = Duration.ofSeconds(2);
        Duration toolTime = Duration.ofMillis(500);
        TaskMetrics metrics = TaskMetrics.builder()
                .inputTokens(1000L)
                .outputTokens(500L)
                .totalTokens(1500L)
                .llmLatency(llmLatency)
                .toolExecutionTime(toolTime)
                .llmCallCount(3)
                .toolCallCount(5)
                .delegationCount(1)
                .build();

        assertThat(metrics.getInputTokens()).isEqualTo(1000L);
        assertThat(metrics.getOutputTokens()).isEqualTo(500L);
        assertThat(metrics.getTotalTokens()).isEqualTo(1500L);
        assertThat(metrics.getLlmLatency()).isEqualTo(llmLatency);
        assertThat(metrics.getToolExecutionTime()).isEqualTo(toolTime);
        assertThat(metrics.getLlmCallCount()).isEqualTo(3);
        assertThat(metrics.getToolCallCount()).isEqualTo(5);
        assertThat(metrics.getDelegationCount()).isEqualTo(1);
    }
}
