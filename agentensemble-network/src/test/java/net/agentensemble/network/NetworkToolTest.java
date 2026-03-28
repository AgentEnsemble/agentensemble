package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTool} construction, naming, and factory methods.
 */
class NetworkToolTest {

    @Test
    void name_returnsEnsembleDotTool() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void description_mentionsEnsembleAndTool() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.description()).contains("ensemble").contains("kitchen");
        assertThat(tool.description()).contains("tool").contains("check-inventory");
    }

    @Test
    void defaultExecutionTimeout_is30Seconds() {
        assertThat(NetworkTool.DEFAULT_EXECUTION_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void from_withDefaultTimeout_usesDefaultExecutionTimeout() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.executionTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void from_withCustomTimeout_usesProvidedTimeout() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration customTimeout = Duration.ofSeconds(60);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", customTimeout, registry);

        assertThat(tool.executionTimeout()).isEqualTo(customTimeout);
    }

    @Test
    void ensembleName_returnsConfiguredValue() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void toolName_returnsConfiguredValue() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.toolName()).isEqualTo("check-inventory");
    }

    @Test
    void executionTimeout_returnsConfiguredValue() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration timeout = Duration.ofSeconds(90);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", timeout, registry);

        assertThat(tool.executionTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void from_factoryCreatesCorrectInstance() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.ensembleName()).isEqualTo("kitchen");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
        assertThat(tool.executionTimeout()).isEqualTo(NetworkTool.DEFAULT_EXECUTION_TIMEOUT);
        assertThat(tool.name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void from_factoryWithCustomTimeoutCreatesCorrectInstance() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration timeout = Duration.ofSeconds(120);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", timeout, registry);

        assertThat(tool.ensembleName()).isEqualTo("kitchen");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
        assertThat(tool.executionTimeout()).isEqualTo(timeout);
        assertThat(tool.name()).isEqualTo("kitchen.check-inventory");
    }
}
