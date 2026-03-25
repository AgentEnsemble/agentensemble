package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import java.time.Duration;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.dashboard.EnsembleDashboard;
import net.agentensemble.ensemble.EnsembleLifecycleState;
import net.agentensemble.exception.AgentEnsembleException;
import net.agentensemble.review.ReviewHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the long-running ensemble lifecycle ({@code start()}/{@code stop()}).
 */
class EnsembleLifecycleTest {

    private ChatModel model;
    private EnsembleDashboard dashboard;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));
        when(dashboard.reviewHandler()).thenReturn(mock(ReviewHandler.class));
    }

    /**
     * Build an ensemble with the dashboard wired directly (bypassing the convenience
     * webDashboard() method which auto-starts). This lets us test start()/stop() without
     * the builder's auto-start interfering.
     */
    private Ensemble buildWithDashboard() {
        when(dashboard.isRunning()).thenReturn(true);
        return Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .dashboard(dashboard)
                .ownsDashboardLifecycle(false)
                .listener(dashboard.streamingListener())
                .reviewHandler(dashboard.reviewHandler())
                .build();
    }

    @Test
    void lifecycleStateIsNullBeforeStart() {
        Ensemble ensemble = buildWithDashboard();
        assertThat(ensemble.getLifecycleState()).isNull();
    }

    @Test
    void startTransitionsToReady() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.start(7329);

        assertThat(ensemble.getLifecycleState()).isEqualTo(EnsembleLifecycleState.READY);
    }

    @Test
    void startIsIdempotentWhenReady() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.start(7329);
        ensemble.start(7329);

        assertThat(ensemble.getLifecycleState()).isEqualTo(EnsembleLifecycleState.READY);
    }

    @Test
    void stopTransitionsToStopped() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.start(7329);
        ensemble.stop();

        assertThat(ensemble.getLifecycleState()).isEqualTo(EnsembleLifecycleState.STOPPED);
    }

    @Test
    void stopIsIdempotentWhenNeverStarted() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.stop();

        assertThat(ensemble.getLifecycleState()).isNull();
    }

    @Test
    void stopIsIdempotentWhenAlreadyStopped() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.start(7329);
        ensemble.stop();
        ensemble.stop();

        assertThat(ensemble.getLifecycleState()).isEqualTo(EnsembleLifecycleState.STOPPED);
    }

    @Test
    void startCallsDashboardStartWhenNotRunning() {
        when(dashboard.isRunning()).thenReturn(false);
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .dashboard(dashboard)
                .ownsDashboardLifecycle(false)
                .build();

        ensemble.start(7329);

        verify(dashboard).start();
    }

    @Test
    void startSkipsDashboardStartWhenAlreadyRunning() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.start(7329);

        // dashboard.isRunning() returns true, so start() should not be called
        verify(dashboard, times(0)).start();
    }

    @Test
    void stopCallsDashboardStop() {
        Ensemble ensemble = buildWithDashboard();

        ensemble.start(7329);
        ensemble.stop();

        verify(dashboard).stop();
    }

    @Test
    void drainTimeoutDefaultsToFiveMinutes() {
        Ensemble ensemble = buildWithDashboard();
        assertThat(ensemble.getDrainTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void drainTimeoutIsConfigurable() {
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .drainTimeout(Duration.ofSeconds(30))
                .build();
        assertThat(ensemble.getDrainTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void startFailureTransitionsToStopped() {
        when(dashboard.isRunning()).thenReturn(false);
        // Make dashboard.start() throw
        org.mockito.Mockito.doThrow(new RuntimeException("bind failed"))
                .when(dashboard)
                .start();

        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .dashboard(dashboard)
                .ownsDashboardLifecycle(false)
                .build();

        assertThatThrownBy(() -> ensemble.start(7329))
                .isInstanceOf(AgentEnsembleException.class)
                .hasMessageContaining("Failed to start ensemble");

        assertThat(ensemble.getLifecycleState()).isEqualTo(EnsembleLifecycleState.STOPPED);
    }

    @Test
    void sharedCapabilitiesDefaultToNull() {
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .build();
        // When no sharedCapabilities are set via shareTask/shareTool, the field is null
        // (Lombok does not initialize non-@Singular, non-@Builder.Default fields).
        assertThat(ensemble.getSharedCapabilities()).isNull();
    }
}
