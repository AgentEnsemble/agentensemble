package net.agentensemble.metrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Per-token cost rates for a specific LLM model or provider.
 *
 * <p>Token counts depend on the LLM provider populating {@code ChatResponse} usage metadata.
 * When token counts are unavailable ({@code -1}), cost estimation is skipped and
 * {@link #estimate(long, long)} returns {@code null}.
 *
 * <p>Example (OpenAI GPT-4o pricing as of early 2026):
 * <pre>
 * CostConfiguration gpt4o = CostConfiguration.builder()
 *     .inputTokenRate(new BigDecimal("0.0000025"))   // $2.50 per 1M input tokens
 *     .outputTokenRate(new BigDecimal("0.0000100"))  // $10.00 per 1M output tokens
 *     .currency("USD")
 *     .build();
 * </pre>
 */
@Value
@Builder
public class CostConfiguration {

    /**
     * Cost per input token.
     *
     * <p>Example: {@code new BigDecimal("0.0000025")} for $2.50 per million input tokens.
     */
    @NonNull
    BigDecimal inputTokenRate;

    /**
     * Cost per output token.
     *
     * <p>Example: {@code new BigDecimal("0.0000100")} for $10.00 per million output tokens.
     */
    @NonNull
    BigDecimal outputTokenRate;

    /**
     * ISO 4217 currency code for the rates (e.g., {@code "USD"}).
     * Informational only; not used in calculations.
     */
    @Builder.Default
    String currency = "USD";

    /**
     * Estimate cost from token counts.
     *
     * <p>Returns {@code null} if either token count is {@code -1} (unknown), preserving
     * the convention that unknown values propagate as {@code null} rather than incorrect zeros.
     *
     * @param inputTokens  number of input tokens, or {@code -1} if unknown
     * @param outputTokens number of output tokens, or {@code -1} if unknown
     * @return estimated cost, or {@code null} if token counts are unknown
     */
    public CostEstimate estimate(long inputTokens, long outputTokens) {
        if (inputTokens < 0 || outputTokens < 0) {
            return null;
        }
        BigDecimal inputCost =
                inputTokenRate.multiply(BigDecimal.valueOf(inputTokens)).setScale(8, RoundingMode.HALF_UP);
        BigDecimal outputCost =
                outputTokenRate.multiply(BigDecimal.valueOf(outputTokens)).setScale(8, RoundingMode.HALF_UP);
        BigDecimal totalCost = inputCost.add(outputCost);
        return CostEstimate.builder()
                .inputCost(inputCost)
                .outputCost(outputCost)
                .totalCost(totalCost)
                .currency(currency)
                .build();
    }
}
