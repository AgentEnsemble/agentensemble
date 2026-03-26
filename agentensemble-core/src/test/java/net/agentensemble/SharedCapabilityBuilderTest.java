package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.ensemble.SharedCapability;
import net.agentensemble.ensemble.SharedCapabilityType;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.tool.AgentTool;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code shareTask()} and {@code shareTool()} builder methods
 * on {@link Ensemble.EnsembleBuilder}, and validation of shared capabilities.
 */
class SharedCapabilityBuilderTest {

    private final ChatModel model = mock(ChatModel.class);

    @Test
    void shareTaskAddsCapability() {
        Task task = Task.of("Prepare a meal as specified");
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .shareTask("prepare-meal", task)
                .build();

        List<SharedCapability> caps = ensemble.getSharedCapabilities();
        assertThat(caps).hasSize(1);
        assertThat(caps.get(0).name()).isEqualTo("prepare-meal");
        assertThat(caps.get(0).type()).isEqualTo(SharedCapabilityType.TASK);
    }

    @Test
    void shareToolAddsCapability() {
        AgentTool tool = mock(AgentTool.class);
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .shareTool("check-inventory", tool)
                .build();

        List<SharedCapability> caps = ensemble.getSharedCapabilities();
        assertThat(caps).hasSize(1);
        assertThat(caps.get(0).name()).isEqualTo("check-inventory");
        assertThat(caps.get(0).type()).isEqualTo(SharedCapabilityType.TOOL);
    }

    @Test
    void multipleSharedCapabilities() {
        AgentTool tool = mock(AgentTool.class);
        Task task = Task.of("Prepare a meal");
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .shareTask("prepare-meal", task)
                .shareTool("check-inventory", tool)
                .shareTool("dietary-check", tool)
                .build();

        assertThat(ensemble.getSharedCapabilities()).hasSize(3);
    }

    @Test
    void noSharedCapabilitiesReturnsEmpty() {
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .build();

        // When no shareTask/shareTool is called, the custom builder initializes to List.of()
        assertThat(ensemble.getSharedCapabilities()).isEmpty();
    }

    @Test
    void shareTaskWithNullNameThrows() {
        assertThatThrownBy(() -> Ensemble.builder().shareTask(null, Task.of("task")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shareTaskWithBlankNameThrows() {
        assertThatThrownBy(() -> Ensemble.builder().shareTask("  ", Task.of("task")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shareTaskWithNullTaskThrows() {
        assertThatThrownBy(() -> Ensemble.builder().shareTask("name", (Task) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("task");
    }

    @Test
    void shareToolWithNullNameThrows() {
        assertThatThrownBy(() -> Ensemble.builder().shareTool(null, mock(AgentTool.class)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void shareToolWithBlankNameThrows() {
        assertThatThrownBy(() -> Ensemble.builder().shareTool("  ", mock(AgentTool.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void shareToolWithNullToolThrows() {
        assertThatThrownBy(() -> Ensemble.builder().shareTool("name", (AgentTool) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tool");
    }

    @Test
    void duplicateSharedCapabilityNameFailsValidation() {
        AgentTool tool = mock(AgentTool.class);
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .shareTask("overlap", Task.of("task"))
                .shareTool("overlap", tool)
                .build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate shared capability name")
                .hasMessageContaining("overlap");
    }

    @Test
    void sharedCapabilitiesDoNotAffectOneShotValidation() {
        AgentTool tool = mock(AgentTool.class);
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .shareTask("prepare-meal", Task.of("Prepare a meal"))
                .shareTool("check-inventory", tool)
                .build();

        // Validation passes -- shared capabilities do not block one-shot mode.
        // The actual run() will fail because the mock model cannot chat, but that is
        // after validation succeeds. We verify by calling the validator directly.
        new EnsembleValidator(ensemble).validate();
    }

    @Test
    void sharedCapabilitiesListIsImmutable() {
        Task task = Task.of("Prepare a meal");
        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("main task"))
                .shareTask("prepare-meal", task)
                .build();

        List<SharedCapability> caps = ensemble.getSharedCapabilities();
        assertThatThrownBy(() -> caps.add(new SharedCapability("hacked", "hacked", SharedCapabilityType.TASK)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
