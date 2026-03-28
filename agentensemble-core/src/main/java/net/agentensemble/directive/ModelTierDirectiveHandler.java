package net.agentensemble.directive;

import net.agentensemble.Ensemble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code SET_MODEL_TIER} control plane directives.
 *
 * <p>Switches the ensemble between its primary and fallback LLM models at runtime.
 * The switch applies to new tasks only; in-flight tasks continue with their current model.
 *
 * <p>Accepted values:
 * <ul>
 *   <li>{@code "FALLBACK"} -- switch to the fallback model</li>
 *   <li>{@code "PRIMARY"} -- switch back to the primary model</li>
 * </ul>
 */
final class ModelTierDirectiveHandler implements DirectiveHandler {

    private static final Logger log = LoggerFactory.getLogger(ModelTierDirectiveHandler.class);

    @Override
    public void handle(Directive directive, Ensemble ensemble) {
        String tier = directive.value();
        if (tier == null) {
            log.warn("SET_MODEL_TIER directive has no value; ignoring");
            return;
        }
        switch (tier.toUpperCase()) {
            case "FALLBACK" -> ensemble.switchToFallbackModel();
            case "PRIMARY" -> ensemble.switchToPrimaryModel();
            default -> log.warn("Unknown model tier '{}'; ignoring", tier);
        }
    }
}
