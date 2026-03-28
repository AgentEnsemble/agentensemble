package net.agentensemble.directive;

import net.agentensemble.Ensemble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code APPLY_PROFILE} control plane directives.
 *
 * <p>Applies a named operational profile to the ensemble. Profiles are application-defined
 * configurations that can modify concurrency limits, model tiers, or other ensemble behavior.
 *
 * <p>In v3.0.0-rc, this handler logs the profile name. Application-specific behavior
 * should be implemented by registering a custom {@link DirectiveHandler} for
 * {@code "APPLY_PROFILE"} that replaces this default.
 */
final class ProfileDirectiveHandler implements DirectiveHandler {

    private static final Logger log = LoggerFactory.getLogger(ProfileDirectiveHandler.class);

    @Override
    public void handle(Directive directive, Ensemble ensemble) {
        String profileName = directive.value();
        if (profileName == null || profileName.isBlank()) {
            log.warn("APPLY_PROFILE directive has no value; ignoring");
            return;
        }
        log.info("Applying operational profile '{}' to ensemble", profileName);
        // Application-specific profile logic should be implemented by registering
        // a custom DirectiveHandler via DirectiveDispatcher.registerHandler().
    }
}
