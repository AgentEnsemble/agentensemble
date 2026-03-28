package net.agentensemble.network.profile;

import java.util.Map;
import java.util.Objects;
import net.agentensemble.Ensemble;
import net.agentensemble.directive.Directive;
import net.agentensemble.directive.DirectiveHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DirectiveHandler} that applies operational profiles via the
 * {@code APPLY_PROFILE} control plane directive.
 *
 * <p>Register via:
 * <pre>
 * directiveDispatcher.registerHandler("APPLY_PROFILE",
 *     new NetworkProfileDirectiveHandler(applier, profiles));
 * </pre>
 */
public class NetworkProfileDirectiveHandler implements DirectiveHandler {

    private static final Logger log = LoggerFactory.getLogger(NetworkProfileDirectiveHandler.class);

    private final ProfileApplier applier;
    private final Map<String, NetworkProfile> profiles;

    public NetworkProfileDirectiveHandler(ProfileApplier applier, Map<String, NetworkProfile> profiles) {
        this.applier = Objects.requireNonNull(applier);
        this.profiles = Objects.requireNonNull(profiles);
    }

    @Override
    public void handle(Directive directive, Ensemble ensemble) {
        String profileName = directive.value();
        if (profileName == null || profileName.isBlank()) {
            log.warn("APPLY_PROFILE directive has no value; ignoring");
            return;
        }
        NetworkProfile profile = profiles.get(profileName);
        if (profile == null) {
            log.warn("Unknown profile '{}'; ignoring APPLY_PROFILE directive", profileName);
            return;
        }
        applier.apply(profile);
    }
}
