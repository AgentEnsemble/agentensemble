package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.dashboard.EnsembleDashboard;
import net.agentensemble.ensemble.ManagedResource;
import net.agentensemble.review.ReviewHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link Ensemble.EnsembleBuilder#managedResource(ManagedResource)} wiring.
 *
 * <p>Verifies the ownership contract (owned vs caller-owned), that owned resources are
 * started by the builder and closed by {@link Ensemble#stop()}, and that caller-owned
 * resources survive ensemble shutdown.
 */
class EnsembleManagedResourceTest {

    private ChatModel model;
    private EnsembleDashboard dashboard;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(true);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));
        when(dashboard.reviewHandler()).thenReturn(mock(ReviewHandler.class));
    }

    /** A controllable test resource that records start/close call counts. */
    private static final class TestResource implements ManagedResource {
        boolean running;
        int starts;
        int closes;

        TestResource(boolean initiallyRunning) {
            this.running = initiallyRunning;
        }

        @Override
        public void start() {
            starts++;
            running = true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void close() {
            closes++;
            running = false;
        }
    }

    private Ensemble.EnsembleBuilder baseBuilder() {
        return Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .dashboard(dashboard)
                .ownsDashboardLifecycle(false);
    }

    @Test
    void ownedResource_isStartedByBuilder() {
        TestResource fs = new TestResource(false);

        baseBuilder().managedResource(fs).build();

        // Builder must start the resource immediately so callers can do
        // `.managedResource(fs).agent(... fs.tools() ...)` in a single chain.
        assertThat(fs.starts).isEqualTo(1);
        assertThat(fs.running).isTrue();
    }

    @Test
    void callerOwnedResource_isNotStartedByBuilder() {
        // Resource is already running -- caller retains ownership.
        TestResource fs = new TestResource(true);

        baseBuilder().managedResource(fs).build();

        // Builder must NOT call start() on a resource the caller is already managing.
        assertThat(fs.starts).isEqualTo(0);
    }

    @Test
    void ownedResource_isClosedByStop() {
        TestResource fs = new TestResource(false);
        Ensemble ensemble = baseBuilder().managedResource(fs).build();

        ensemble.start(7329);
        ensemble.stop();

        // Owned resource is closed when the ensemble stops.
        assertThat(fs.closes).isEqualTo(1);
        assertThat(fs.running).isFalse();
    }

    @Test
    void callerOwnedResource_survivesStop() {
        TestResource fs = new TestResource(true); // already running -> caller-owned
        Ensemble ensemble = baseBuilder().managedResource(fs).build();

        ensemble.start(7329);
        ensemble.stop();

        // Caller-owned resource must NOT be closed by ensemble.stop() -- the caller
        // retains lifecycle responsibility, exactly like the dashboard ownership contract.
        assertThat(fs.closes).isEqualTo(0);
        assertThat(fs.running).isTrue();
    }

    @Test
    void start_revivesClosedOwnedResource() {
        TestResource fs = new TestResource(false);
        Ensemble ensemble = baseBuilder().managedResource(fs).build();

        // Builder started it once.
        assertThat(fs.starts).isEqualTo(1);

        // Simulate something closing the resource between the build and start(int) calls
        // (e.g. a previous Ensemble.stop() in a test, or external interference).
        fs.close();
        assertThat(fs.running).isFalse();

        ensemble.start(7329);

        // start(int) must revive the resource so tasks have a working tool backend.
        assertThat(fs.starts).isEqualTo(2);
        assertThat(fs.running).isTrue();
    }

    @Test
    void multipleResources_areAllStartedAndOnlyOwnedAreClosed() {
        TestResource owned = new TestResource(false);
        TestResource caller = new TestResource(true);
        Ensemble ensemble =
                baseBuilder().managedResource(owned).managedResource(caller).build();

        ensemble.start(7329);
        ensemble.stop();

        assertThat(owned.starts).isEqualTo(1);
        assertThat(owned.closes).isEqualTo(1);
        assertThat(caller.starts).isEqualTo(0);
        assertThat(caller.closes).isEqualTo(0);
    }

    @Test
    void mockResource_isStartedAndClosedThroughLifecycle() {
        // Belt-and-braces: also verify with a Mockito mock so we catch any signature drift
        // (e.g. someone adding new methods to ManagedResource without wiring them in).
        ManagedResource mocked = mock(ManagedResource.class);
        when(mocked.isRunning()).thenReturn(false);
        Ensemble ensemble = baseBuilder().managedResource(mocked).build();

        verify(mocked).start();

        when(mocked.isRunning()).thenReturn(true);
        ensemble.start(7329);
        ensemble.stop();

        verify(mocked, times(1)).close();
    }
}
