package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import net.agentensemble.web.protocol.WorkRequest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IngressCoordinator}.
 */
class IngressCoordinatorTest {

    // ========================
    // startAll
    // ========================

    @Test
    void startAll_startsAllSources() {
        IngressSource s1 = mockSource("source-1");
        IngressSource s2 = mockSource("source-2");
        IngressSource s3 = mockSource("source-3");

        IngressCoordinator coordinator =
                IngressCoordinator.builder().add(s1).add(s2).add(s3).build();

        Consumer<WorkRequest> sink = r -> {};
        coordinator.startAll(sink);

        verify(s1).start(sink);
        verify(s2).start(sink);
        verify(s3).start(sink);
    }

    @Test
    void startAll_nullSink_throwsNPE() {
        IngressSource s1 = mockSource("source-1");
        IngressCoordinator coordinator = IngressCoordinator.builder().add(s1).build();

        assertThatThrownBy(() -> coordinator.startAll(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void startAll_idempotent() {
        IngressSource s1 = mockSource("source-1");
        IngressCoordinator coordinator = IngressCoordinator.builder().add(s1).build();

        Consumer<WorkRequest> sink = r -> {};
        coordinator.startAll(sink);
        coordinator.startAll(sink);

        // start() should only be called once
        verify(s1).start(sink);
    }

    // ========================
    // stopAll
    // ========================

    @Test
    void stopAll_stopsAllSources() {
        IngressSource s1 = mockSource("source-1");
        IngressSource s2 = mockSource("source-2");
        IngressSource s3 = mockSource("source-3");

        IngressCoordinator coordinator =
                IngressCoordinator.builder().add(s1).add(s2).add(s3).build();

        coordinator.startAll(r -> {});
        coordinator.stopAll();

        verify(s1).stop();
        verify(s2).stop();
        verify(s3).stop();
    }

    @Test
    void stopAll_handlesExceptions() {
        IngressSource s1 = mockSource("source-1");
        IngressSource s2 = mockSource("source-2");
        IngressSource s3 = mockSource("source-3");

        doThrow(new RuntimeException("boom")).when(s2).stop();

        IngressCoordinator coordinator =
                IngressCoordinator.builder().add(s1).add(s2).add(s3).build();

        coordinator.startAll(r -> {});
        coordinator.stopAll();

        // All sources should have stop() called, even when s2 throws
        verify(s1).stop();
        verify(s2).stop();
        verify(s3).stop();
    }

    @Test
    void stopAll_beforeStart_isNoOp() {
        IngressSource s1 = mockSource("source-1");
        IngressCoordinator coordinator = IngressCoordinator.builder().add(s1).build();

        coordinator.stopAll();

        verify(s1, never()).stop();
    }

    // ========================
    // close
    // ========================

    @Test
    void close_stopsAll() {
        IngressSource s1 = mockSource("source-1");
        IngressCoordinator coordinator = IngressCoordinator.builder().add(s1).build();

        coordinator.startAll(r -> {});
        coordinator.close();

        verify(s1).stop();
    }

    // ========================
    // sources
    // ========================

    @Test
    void sources_returnsUnmodifiableList() {
        IngressSource s1 = mockSource("source-1");
        IngressSource s2 = mockSource("source-2");

        IngressCoordinator coordinator =
                IngressCoordinator.builder().add(s1).add(s2).build();

        assertThat(coordinator.sources()).containsExactly(s1, s2);
        assertThatThrownBy(() -> coordinator.sources().add(mockSource("s3")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Builder
    // ========================

    @Test
    void builder_empty_throwsISE() {
        assertThatThrownBy(() -> IngressCoordinator.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one IngressSource is required");
    }

    @Test
    void builder_addNull_throwsNPE() {
        assertThatThrownBy(() -> IngressCoordinator.builder().add(null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // Helpers
    // ========================

    private static IngressSource mockSource(String name) {
        IngressSource source = mock(IngressSource.class);
        when(source.name()).thenReturn(name);
        return source;
    }
}
