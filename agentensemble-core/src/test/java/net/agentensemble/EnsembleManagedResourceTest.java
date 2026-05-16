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
    void ensembleWithNoManagedResource_doesNotNpeOnStop() {
        // The managedResources / ownedManagedResources fields default to non-null empty
        // collections via the custom builder's shadow-field pattern. This test locks that
        // in -- if Lombok ever stops honoring the override and the fields go null,
        // startManagedResources()/closeOwnedManagedResources() would NPE in stop().
        Ensemble ensemble = baseBuilder().build();

        ensemble.start(7329);
        ensemble.stop(); // must not throw NPE

        assertThat(ensemble.getLifecycleState()).isNotNull();
    }

    @Test
    void start_propagatesResourceFailureAsAgentEnsembleException() {
        // A managed resource that fails to start during ensemble.start(int) must surface
        // the failure loudly, not silently transition to READY with a broken tool backend.
        ManagedResource exploding = mock(ManagedResource.class);
        when(exploding.isRunning()).thenReturn(false, false); // false at builder time, still false at start(int)
        org.mockito.Mockito.doNothing() // builder's start() succeeds...
                .doThrow(new RuntimeException("transport failed")) // ...but start(int)'s revive fails
                .when(exploding)
                .start();
        Ensemble ensemble = baseBuilder().managedResource(exploding).build();

        // Manually flip isRunning back to false to simulate the resource dying between
        // build() and start(int).
        when(exploding.isRunning()).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ensemble.start(7329))
                .isInstanceOf(net.agentensemble.exception.AgentEnsembleException.class)
                .hasMessageContaining("Failed to start ensemble");
    }

    @Test
    void callerOwnedResource_isNotRevivedIfClosedExternally() {
        // Caller-owned semantics are symmetric: the ensemble doesn't close it on stop(),
        // and it also doesn't restart it. If the caller closes their own resource between
        // ensemble.start() and a run, the framework leaves it alone -- that's the
        // contract.
        TestResource caller = new TestResource(true);
        Ensemble ensemble = baseBuilder().managedResource(caller).build();

        // Caller closes their resource externally.
        caller.close();
        assertThat(caller.running).isFalse();

        ensemble.start(7329);

        // start() must NOT have revived the caller-owned resource.
        assertThat(caller.starts).isEqualTo(0);
        assertThat(caller.running).isFalse();
    }

    @Test
    void withTasks_copiesManagedResourcesAsCallerOwned() {
        // The Ensemble Control API rebuilds the ensemble via withTasks() for every
        // Level 2/3 run. The copy must see the same managedResources so child runs
        // can still reach MCP-backed tools, but it must NOT take ownership -- the
        // template still owns the lifecycle and will close it.
        TestResource fs = new TestResource(false);
        Ensemble template = baseBuilder().managedResource(fs).build();
        assertThat(fs.starts).isEqualTo(1);

        Ensemble child = template.withTasks(java.util.List.of(Task.of("child task")));

        // Child sees the resource list.
        assertThat(child.getManagedResources()).containsExactly(fs);

        // Child must NOT have started the resource again (it was already running, so
        // managedResource() correctly classified it as caller-owned for the child).
        assertThat(fs.starts).isEqualTo(1);

        // Child.stop() must NOT close the template's resource.
        child.start(7329);
        child.stop();
        assertThat(fs.closes).isEqualTo(0);
        assertThat(fs.running).isTrue();

        // Template still owns the resource and closes it on its own stop.
        template.start(7329);
        template.stop();
        assertThat(fs.closes).isEqualTo(1);
    }

    @Test
    void withAdditionalListener_copiesManagedResourcesAsCallerOwned() {
        TestResource fs = new TestResource(false);
        Ensemble template = baseBuilder().managedResource(fs).build();

        Ensemble child = template.withAdditionalListener(mock(EnsembleListener.class));

        assertThat(child.getManagedResources()).containsExactly(fs);
        assertThat(fs.starts).isEqualTo(1);

        child.start(7329);
        child.stop();
        assertThat(fs.closes).isEqualTo(0);
        assertThat(fs.running).isTrue();
    }

    @Test
    void getManagedResources_returnsImmutableList() {
        TestResource fs = new TestResource(false);
        Ensemble ensemble = baseBuilder().managedResource(fs).build();

        java.util.List<ManagedResource> exposed = ensemble.getManagedResources();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> exposed.add(new TestResource(false)))
                .isInstanceOf(UnsupportedOperationException.class);
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
