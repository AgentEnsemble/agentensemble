package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTaskBuilder}.
 */
class NetworkTaskBuilderTest {

    @Test
    void builder_defaultMode_isAwait() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.builder()
                .ensembleName("kitchen")
                .taskName("prepare-meal")
                .clientRegistry(registry)
                .build();

        assertThat(task.mode()).isEqualTo(RequestMode.AWAIT);
    }

    @Test
    void builder_asyncMode_requiresOnComplete() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTask.builder()
                        .ensembleName("kitchen")
                        .taskName("prepare-meal")
                        .clientRegistry(registry)
                        .mode(RequestMode.ASYNC)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onComplete");
    }

    @Test
    void builder_awaitWithDeadline_requiresDeadline() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTask.builder()
                        .ensembleName("kitchen")
                        .taskName("prepare-meal")
                        .clientRegistry(registry)
                        .mode(RequestMode.AWAIT_WITH_DEADLINE)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    void builder_missingEnsembleName_throwsNPE() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTask.builder()
                        .taskName("prepare-meal")
                        .clientRegistry(registry)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensembleName");
    }

    @Test
    void builder_missingTaskName_throwsNPE() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTask.builder()
                        .ensembleName("kitchen")
                        .clientRegistry(registry)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskName");
    }

    @Test
    void builder_missingClientRegistry_throwsNPE() {
        assertThatThrownBy(() -> NetworkTask.builder()
                        .ensembleName("kitchen")
                        .taskName("prepare-meal")
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRegistry");
    }

    @Test
    void builder_asyncMode_withOnComplete_builds() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.builder()
                .ensembleName("kitchen")
                .taskName("prepare-meal")
                .clientRegistry(registry)
                .mode(RequestMode.ASYNC)
                .onComplete(result -> {})
                .build();

        assertThat(task.mode()).isEqualTo(RequestMode.ASYNC);
        assertThat(task.ensembleName()).isEqualTo("kitchen");
        assertThat(task.taskName()).isEqualTo("prepare-meal");
    }

    @Test
    void builder_deadlineMode_withDeadline_builds() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration deadline = Duration.ofSeconds(5);
        NetworkTask task = NetworkTask.builder()
                .ensembleName("kitchen")
                .taskName("prepare-meal")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT_WITH_DEADLINE)
                .deadline(deadline)
                .build();

        assertThat(task.mode()).isEqualTo(RequestMode.AWAIT_WITH_DEADLINE);
        assertThat(task.deadline()).isEqualTo(deadline);
        assertThat(task.deadlineAction()).isEqualTo(DeadlineAction.RETURN_TIMEOUT_ERROR);
    }

    @Test
    void from_factoryMethod_backwardCompatible() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.ensembleName()).isEqualTo("kitchen");
        assertThat(task.taskName()).isEqualTo("prepare-meal");
        assertThat(task.executionTimeout()).isEqualTo(NetworkTask.DEFAULT_EXECUTION_TIMEOUT);
        assertThat(task.mode()).isEqualTo(RequestMode.AWAIT);
    }

    @Test
    void from_factoryMethod_withTimeout_backwardCompatible() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration timeout = Duration.ofMinutes(10);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", timeout, registry);

        assertThat(task.ensembleName()).isEqualTo("kitchen");
        assertThat(task.taskName()).isEqualTo("prepare-meal");
        assertThat(task.executionTimeout()).isEqualTo(timeout);
        assertThat(task.mode()).isEqualTo(RequestMode.AWAIT);
    }
}
