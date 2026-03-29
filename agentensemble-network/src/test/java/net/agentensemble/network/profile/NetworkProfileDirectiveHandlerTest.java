package net.agentensemble.network.profile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Map;
import net.agentensemble.Ensemble;
import net.agentensemble.directive.Directive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkProfileDirectiveHandler}.
 */
class NetworkProfileDirectiveHandlerTest {

    private ProfileApplier applier;
    private NetworkProfile sportingEvent;
    private NetworkProfileDirectiveHandler handler;
    private Ensemble ensemble;

    @BeforeEach
    void setUp() {
        applier = mock(ProfileApplier.class);
        sportingEvent = NetworkProfile.builder()
                .name("sporting-event")
                .ensemble("kitchen", Capacity.replicas(4).maxConcurrent(50))
                .build();

        Map<String, NetworkProfile> profiles = Map.of("sporting-event", sportingEvent);
        handler = new NetworkProfileDirectiveHandler(applier, profiles);
        ensemble = mock(Ensemble.class);
    }

    @Test
    void findsAndAppliesKnownProfile() {
        Directive directive =
                new Directive("d1", "admin", null, "APPLY_PROFILE", "sporting-event", Instant.now(), null);

        handler.handle(directive, ensemble);

        verify(applier).apply(sportingEvent);
    }

    @Test
    void unknownProfileLogsWarning() {
        Directive directive =
                new Directive("d2", "admin", null, "APPLY_PROFILE", "unknown-profile", Instant.now(), null);

        handler.handle(directive, ensemble);

        verify(applier, never()).apply(any());
    }

    @Test
    void nullValueIgnored() {
        Directive directive = new Directive("d3", "admin", null, "APPLY_PROFILE", null, Instant.now(), null);

        handler.handle(directive, ensemble);

        verify(applier, never()).apply(any());
    }

    @Test
    void blankValueIgnored() {
        Directive directive = new Directive("d4", "admin", null, "APPLY_PROFILE", "  ", Instant.now(), null);

        handler.handle(directive, ensemble);

        verify(applier, never()).apply(any());
    }

    @Test
    void emptyValueIgnored() {
        Directive directive = new Directive("d5", "admin", null, "APPLY_PROFILE", "", Instant.now(), null);

        handler.handle(directive, ensemble);

        verify(applier, never()).apply(any());
    }
}
