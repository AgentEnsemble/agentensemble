package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkToolBuilder}.
 */
class NetworkToolBuilderTest {

    @Test
    void builder_defaultMode_isAwait() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .build();

        assertThat(tool.mode()).isEqualTo(RequestMode.AWAIT);
    }

    @Test
    void builder_asyncMode_requiresOnComplete() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTool.builder()
                        .ensembleName("kitchen")
                        .toolName("check-inventory")
                        .clientRegistry(registry)
                        .mode(RequestMode.ASYNC)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onComplete");
    }

    @Test
    void builder_awaitWithDeadline_requiresDeadline() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTool.builder()
                        .ensembleName("kitchen")
                        .toolName("check-inventory")
                        .clientRegistry(registry)
                        .mode(RequestMode.AWAIT_WITH_DEADLINE)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadline");
    }

    @Test
    void builder_missingEnsembleName_throwsNPE() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTool.builder()
                        .toolName("check-inventory")
                        .clientRegistry(registry)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensembleName");
    }

    @Test
    void builder_missingToolName_throwsNPE() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);

        assertThatThrownBy(() -> NetworkTool.builder()
                        .ensembleName("kitchen")
                        .clientRegistry(registry)
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("toolName");
    }

    @Test
    void builder_missingClientRegistry_throwsNPE() {
        assertThatThrownBy(() -> NetworkTool.builder()
                        .ensembleName("kitchen")
                        .toolName("check-inventory")
                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clientRegistry");
    }

    @Test
    void builder_asyncMode_withOnComplete_builds() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.ASYNC)
                .onComplete(result -> {})
                .build();

        assertThat(tool.mode()).isEqualTo(RequestMode.ASYNC);
        assertThat(tool.ensembleName()).isEqualTo("kitchen");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
    }

    @Test
    void builder_deadlineMode_withDeadline_builds() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration deadline = Duration.ofSeconds(5);
        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT_WITH_DEADLINE)
                .deadline(deadline)
                .build();

        assertThat(tool.mode()).isEqualTo(RequestMode.AWAIT_WITH_DEADLINE);
        assertThat(tool.deadline()).isEqualTo(deadline);
        assertThat(tool.deadlineAction()).isEqualTo(DeadlineAction.RETURN_TIMEOUT_ERROR);
    }

    @Test
    void from_factoryMethod_backwardCompatible() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);

        assertThat(tool.ensembleName()).isEqualTo("kitchen");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
        assertThat(tool.executionTimeout()).isEqualTo(NetworkTool.DEFAULT_EXECUTION_TIMEOUT);
        assertThat(tool.mode()).isEqualTo(RequestMode.AWAIT);
    }

    @Test
    void from_factoryMethod_withTimeout_backwardCompatible() {
        NetworkClientRegistry registry = mock(NetworkClientRegistry.class);
        Duration timeout = Duration.ofSeconds(120);
        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", timeout, registry);

        assertThat(tool.ensembleName()).isEqualTo("kitchen");
        assertThat(tool.toolName()).isEqualTo("check-inventory");
        assertThat(tool.executionTimeout()).isEqualTo(timeout);
        assertThat(tool.mode()).isEqualTo(RequestMode.AWAIT);
    }
}
