package net.agentensemble.directive;

import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.Ensemble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes control plane directives to their registered {@link DirectiveHandler}s by action name.
 *
 * <p>Default handlers are registered for:
 * <ul>
 *   <li>{@code SET_MODEL_TIER} -- switches between primary and fallback LLM models</li>
 *   <li>{@code APPLY_PROFILE} -- applies a named operational profile</li>
 * </ul>
 *
 * <p>Custom handlers can be registered via {@link #registerHandler(String, DirectiveHandler)}.
 */
public final class DirectiveDispatcher {

    private static final Logger log = LoggerFactory.getLogger(DirectiveDispatcher.class);

    private final ConcurrentHashMap<String, DirectiveHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Create a dispatcher with the default handlers registered.
     */
    public DirectiveDispatcher() {
        handlers.put("SET_MODEL_TIER", new ModelTierDirectiveHandler());
        handlers.put("APPLY_PROFILE", new ProfileDirectiveHandler());
    }

    /**
     * Register a handler for a specific action name.
     *
     * <p>Replaces any existing handler for the same action.
     *
     * @param action  the action name; must not be null
     * @param handler the handler to register; must not be null
     */
    public void registerHandler(String action, DirectiveHandler handler) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        handlers.put(action, handler);
    }

    /**
     * Dispatch a control plane directive to its registered handler.
     *
     * <p>If no handler is registered for the directive's action, the directive is
     * logged and ignored.
     *
     * @param directive the control plane directive; must have a non-null action
     * @param ensemble  the target ensemble
     */
    public void dispatch(Directive directive, Ensemble ensemble) {
        if (directive.action() == null) {
            log.warn("Cannot dispatch directive with null action; ignoring");
            return;
        }
        DirectiveHandler handler = handlers.get(directive.action());
        if (handler != null) {
            try {
                handler.handle(directive, ensemble);
            } catch (Exception e) {
                log.warn("DirectiveHandler for '{}' threw exception: {}", directive.action(), e.getMessage(), e);
            }
        } else {
            log.warn("No handler registered for control plane action '{}'; ignoring", directive.action());
        }
    }
}
