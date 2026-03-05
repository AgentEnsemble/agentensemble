package net.agentensemble.metrics;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Estimated monetary cost for a task or ensemble run, computed from token counts and
 * per-token rates configured via {@link CostConfiguration}.
 *
 * <p>Cost estimation is entirely optional. When no {@link CostConfiguration} is supplied
 * to the ensemble, all {@code costEstimate} fields in {@link TaskMetrics} and
 * {@link ExecutionMetrics} are {@code null}.
 */
@Value
@Builder
public class CostEstimate {

    /** Estimated cost for input (prompt) tokens. */
    @NonNull
    BigDecimal inputCost;

    /** Estimated cost for output (completion) tokens. */
    @NonNull
    BigDecimal outputCost;

    /** Total estimated cost ({@code inputCost + outputCost}). */
    @NonNull
    BigDecimal totalCost;

    /**
     * ISO 4217 currency code (e.g., {@code "USD"}).
     * Carries through from {@link CostConfiguration#getCurrency()}.
     */
    @Builder.Default
    String currency = "USD";

    /**
     * Return a new instance that sums this and the other estimate.
     *
     * @param other the estimate to add; may be {@code null} (treated as zero)
     * @return summed estimate using the currency from this instance
     */
    public CostEstimate add(CostEstimate other) {
        if (other == null) {
            return this;
        }
        return CostEstimate.builder()
                .inputCost(this.inputCost.add(other.inputCost))
                .outputCost(this.outputCost.add(other.outputCost))
                .totalCost(this.totalCost.add(other.totalCost))
                .currency(this.currency)
                .build();
    }
}
