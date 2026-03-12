package net.agentensemble.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

class ReflectionConfigTest {

    @Test
    void default_hasNullModelAndNullStrategy() {
        ReflectionConfig config = ReflectionConfig.DEFAULT;

        assertThat(config.model()).isNull();
        assertThat(config.strategy()).isNull();
    }

    @Test
    void builder_setsModel() {
        ChatModel model = mock(ChatModel.class);

        ReflectionConfig config = ReflectionConfig.builder().model(model).build();

        assertThat(config.model()).isSameAs(model);
        assertThat(config.strategy()).isNull();
    }

    @Test
    void builder_setsStrategy() {
        ReflectionStrategy strategy = mock(ReflectionStrategy.class);

        ReflectionConfig config = ReflectionConfig.builder().strategy(strategy).build();

        assertThat(config.strategy()).isSameAs(strategy);
        assertThat(config.model()).isNull();
    }

    @Test
    void builder_setBothModelAndStrategy() {
        ChatModel model = mock(ChatModel.class);
        ReflectionStrategy strategy = mock(ReflectionStrategy.class);

        ReflectionConfig config =
                ReflectionConfig.builder().model(model).strategy(strategy).build();

        assertThat(config.model()).isSameAs(model);
        assertThat(config.strategy()).isSameAs(strategy);
    }

    @Test
    void builder_rejectsNullModel() {
        assertThatNullPointerException()
                .isThrownBy(() -> ReflectionConfig.builder().model(null))
                .withMessageContaining("model");
    }

    @Test
    void builder_rejectsNullStrategy() {
        assertThatNullPointerException()
                .isThrownBy(() -> ReflectionConfig.builder().strategy(null))
                .withMessageContaining("strategy");
    }

    @Test
    void taskBuilder_reflectTrue_setsDefaultConfig() {
        Task task = Task.builder()
                .description("Test task")
                .expectedOutput("Test output")
                .reflect(true)
                .build();

        assertThat(task.getReflectionConfig()).isNotNull();
        assertThat(task.getReflectionConfig()).isSameAs(ReflectionConfig.DEFAULT);
    }

    @Test
    void taskBuilder_reflectFalse_setsNullConfig() {
        Task task = Task.builder()
                .description("Test task")
                .expectedOutput("Test output")
                .reflect(false)
                .build();

        assertThat(task.getReflectionConfig()).isNull();
    }

    @Test
    void taskBuilder_reflectConfig_setsProvidedConfig() {
        ChatModel model = mock(ChatModel.class);
        ReflectionConfig config = ReflectionConfig.builder().model(model).build();

        Task task = Task.builder()
                .description("Test task")
                .expectedOutput("Test output")
                .reflect(config)
                .build();

        assertThat(task.getReflectionConfig()).isSameAs(config);
    }

    @Test
    void taskBuilder_reflectNullConfig_throwsIllegalArgument() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Task.builder()
                        .description("Test task")
                        .expectedOutput("Test output")
                        .reflect((ReflectionConfig) null))
                .withMessageContaining("ReflectionConfig");
    }

    @Test
    void taskBuilder_noReflect_hasNullConfig() {
        Task task = Task.builder()
                .description("Test task")
                .expectedOutput("Test output")
                .build();

        assertThat(task.getReflectionConfig()).isNull();
    }
}
