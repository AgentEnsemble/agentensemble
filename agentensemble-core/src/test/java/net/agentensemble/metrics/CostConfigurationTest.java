package net.agentensemble.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Unit tests for CostConfiguration.estimate() with token count edge cases. */
class CostConfigurationTest {

    private static final CostConfiguration CONFIG = CostConfiguration.builder()
            .inputTokenRate(new BigDecimal("0.000003"))
            .outputTokenRate(new BigDecimal("0.000015"))
            .currency("USD")
            .build();

    @Test
    void testEstimate_knownTokens_computesCost() {
        CostEstimate estimate = CONFIG.estimate(1_000_000, 500_000);

        assertThat(estimate).isNotNull();
        // input: 1_000_000 * 0.000003 = 3.0
        assertThat(estimate.getInputCost()).isEqualByComparingTo(new BigDecimal("3.00000000"));
        // output: 500_000 * 0.000015 = 7.5
        assertThat(estimate.getOutputCost()).isEqualByComparingTo(new BigDecimal("7.50000000"));
        assertThat(estimate.getTotalCost()).isEqualByComparingTo(new BigDecimal("10.50000000"));
        assertThat(estimate.getCurrency()).isEqualTo("USD");
    }

    @Test
    void testEstimate_unknownInputTokens_returnsNull() {
        assertThat(CONFIG.estimate(-1, 500)).isNull();
    }

    @Test
    void testEstimate_unknownOutputTokens_returnsNull() {
        assertThat(CONFIG.estimate(1000, -1)).isNull();
    }

    @Test
    void testEstimate_bothUnknown_returnsNull() {
        assertThat(CONFIG.estimate(-1, -1)).isNull();
    }

    @Test
    void testEstimate_zeroTokens_returnsZeroCost() {
        CostEstimate estimate = CONFIG.estimate(0, 0);

        assertThat(estimate).isNotNull();
        assertThat(estimate.getTotalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void testDefaultCurrency_isUSD() {
        CostConfiguration config = CostConfiguration.builder()
                .inputTokenRate(BigDecimal.ONE)
                .outputTokenRate(BigDecimal.ONE)
                .build();

        assertThat(config.getCurrency()).isEqualTo("USD");
    }

    @Test
    void testCostEstimateAdd_sumsCorrectly() {
        CostEstimate a = CONFIG.estimate(1000, 500);
        CostEstimate b = CONFIG.estimate(2000, 1000);

        CostEstimate sum = a.add(b);

        assertThat(sum.getInputCost()).isEqualByComparingTo(a.getInputCost().add(b.getInputCost()));
        assertThat(sum.getOutputCost()).isEqualByComparingTo(a.getOutputCost().add(b.getOutputCost()));
        assertThat(sum.getTotalCost()).isEqualByComparingTo(a.getTotalCost().add(b.getTotalCost()));
    }

    @Test
    void testCostEstimateAdd_nullOther_returnsSelf() {
        CostEstimate a = CONFIG.estimate(1000, 500);
        assertThat(a.add(null)).isSameAs(a);
    }
}
