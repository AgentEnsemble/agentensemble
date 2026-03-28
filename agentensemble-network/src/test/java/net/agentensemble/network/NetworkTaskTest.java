package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTask} construction, naming, and factory methods.
 */
class NetworkTaskTest {

    @Test
    void name_returnsEnsembleDotTask() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.name()).isEqualTo("kitchen.prepare-meal");
    }

    @Test
    void description_mentionsEnsembleAndTask() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.description()).contains("ensemble").contains("kitchen");
        assertThat(task.description()).contains("task").contains("prepare-meal");
    }

    @Test
    void defaultExecutionTimeout_is30Minutes() {
        assertThat(NetworkTask.DEFAULT_EXECUTION_TIMEOUT).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void from_withDefaultTimeout_usesDefaultExecutionTimeout() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.executionTimeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void from_withCustomTimeout_usesProvidedTimeout() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration customTimeout = Duration.ofMinutes(5);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", customTimeout, registry);

        assertThat(task.executionTimeout()).isEqualTo(customTimeout);
    }

    @Test
    void ensembleName_returnsConfiguredValue() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void taskName_returnsConfiguredValue() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.taskName()).isEqualTo("prepare-meal");
    }

    @Test
    void executionTimeout_returnsConfiguredValue() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration timeout = Duration.ofSeconds(90);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", timeout, registry);

        assertThat(task.executionTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void from_factoryCreatesCorrectInstance() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);

        assertThat(task.ensembleName()).isEqualTo("kitchen");
        assertThat(task.taskName()).isEqualTo("prepare-meal");
        assertThat(task.executionTimeout()).isEqualTo(NetworkTask.DEFAULT_EXECUTION_TIMEOUT);
        assertThat(task.name()).isEqualTo("kitchen.prepare-meal");
    }

    @Test
    void from_factoryWithCustomTimeoutCreatesCorrectInstance() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration timeout = Duration.ofMinutes(10);
        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", timeout, registry);

        assertThat(task.ensembleName()).isEqualTo("kitchen");
        assertThat(task.taskName()).isEqualTo("prepare-meal");
        assertThat(task.executionTimeout()).isEqualTo(timeout);
        assertThat(task.name()).isEqualTo("kitchen.prepare-meal");
    }

    @Test
    void defaultConnectTimeout_is10Seconds() {
        assertThat(NetworkTask.DEFAULT_CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }
}
