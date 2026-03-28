package net.agentensemble.directive;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import net.agentensemble.Ensemble;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DirectiveDispatcher}.
 */
class DirectiveDispatcherTest {

    @Test
    void defaultHandlers_registeredForSetModelTierAndApplyProfile() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        Ensemble ensemble = mock(Ensemble.class);

        Directive setModelTier =
                new Directive("d1", "admin:human", null, "SET_MODEL_TIER", "FALLBACK", Instant.now(), null);

        // Should not throw -- handler exists
        dispatcher.dispatch(setModelTier, ensemble);
        verify(ensemble).switchToFallbackModel();
    }

    @Test
    void dispatch_unknownAction_doesNotThrow() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        Ensemble ensemble = mock(Ensemble.class);

        Directive unknown = new Directive("d1", "admin", null, "UNKNOWN_ACTION", "value", Instant.now(), null);

        dispatcher.dispatch(unknown, ensemble);
        // No handler for UNKNOWN_ACTION -- logged and ignored
    }

    @Test
    void dispatch_nullAction_doesNotThrow() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        Ensemble ensemble = mock(Ensemble.class);

        Directive contextDirective = new Directive("d1", "admin", "VIP guest", null, null, Instant.now(), null);

        dispatcher.dispatch(contextDirective, ensemble);
        verifyNoInteractions(ensemble);
    }

    @Test
    void registerHandler_replacesExisting() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        DirectiveHandler custom = mock(DirectiveHandler.class);
        Ensemble ensemble = mock(Ensemble.class);

        dispatcher.registerHandler("SET_MODEL_TIER", custom);

        Directive d = new Directive("d1", "admin", null, "SET_MODEL_TIER", "PRIMARY", Instant.now(), null);
        dispatcher.dispatch(d, ensemble);

        verify(custom).handle(d, ensemble);
    }

    @Test
    void registerHandler_nullAction_throws() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        assertThatThrownBy(() -> dispatcher.registerHandler(null, (d, e) -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerHandler_nullHandler_throws() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        assertThatThrownBy(() -> dispatcher.registerHandler("ACTION", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dispatch_handlerThrows_doesNotPropagate() {
        DirectiveDispatcher dispatcher = new DirectiveDispatcher();
        Ensemble ensemble = mock(Ensemble.class);

        dispatcher.registerHandler("BOOM", (d, e) -> {
            throw new RuntimeException("handler exploded");
        });

        Directive d = new Directive("d1", "admin", null, "BOOM", "v", Instant.now(), null);

        // Should not throw -- exception is caught and logged
        dispatcher.dispatch(d, ensemble);
    }
}
