package net.agentensemble.directive;

import net.agentensemble.Ensemble;

/**
 * Handler for control plane directives that modify ensemble behavior at runtime.
 *
 * <p>Implementations are registered with a {@link DirectiveDispatcher} under a specific
 * action name (e.g., {@code "SET_MODEL_TIER"}). When a control plane directive arrives
 * with a matching action, the dispatcher invokes the handler.
 *
 * @see DirectiveDispatcher
 * @see ModelTierDirectiveHandler
 */
@FunctionalInterface
public interface DirectiveHandler {

    /**
     * Handle a control plane directive.
     *
     * @param directive the directive to handle; never null
     * @param ensemble  the target ensemble; never null
     */
    void handle(Directive directive, Ensemble ensemble);
}
