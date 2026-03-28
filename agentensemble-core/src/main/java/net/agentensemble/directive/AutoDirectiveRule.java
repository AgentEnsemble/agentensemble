package net.agentensemble.directive;

import java.util.function.Predicate;
import net.agentensemble.metrics.ExecutionMetrics;

/**
 * A rule that automatically fires a control plane directive when a condition is met.
 *
 * <p>Auto-directive rules are evaluated after each task completion. When the condition
 * predicate returns true for the current execution metrics, the associated directive
 * is dispatched through the {@link DirectiveDispatcher}.
 *
 * <p>Example:
 * <pre>
 * AutoDirectiveRule costCeiling = new AutoDirectiveRule(
 *     "cost-ceiling",
 *     metrics -> metrics.getLlmTokensOut() > 100_000,
 *     new Directive(UUID.randomUUID().toString(), "cost-policy:automated",
 *         null, "SET_MODEL_TIER", "FALLBACK", Instant.now(), null));
 * </pre>
 *
 * @param name             human-readable rule name for logging
 * @param condition        predicate evaluated against current metrics after each task
 * @param directiveToFire  the directive to dispatch when the condition is met
 */
public record AutoDirectiveRule(String name, Predicate<ExecutionMetrics> condition, Directive directiveToFire) {

    public AutoDirectiveRule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (condition == null) {
            throw new IllegalArgumentException("condition must not be null");
        }
        if (directiveToFire == null) {
            throw new IllegalArgumentException("directiveToFire must not be null");
        }
    }
}
