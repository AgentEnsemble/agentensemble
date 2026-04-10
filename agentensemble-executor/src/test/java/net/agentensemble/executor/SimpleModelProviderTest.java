package net.agentensemble.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link SimpleModelProvider}. */
class SimpleModelProviderTest {

    // ========================
    // of(ChatModel) factory
    // ========================

    @Test
    void of_singleModel_getDefaultReturnsThatModel() {
        var model = mock(ChatModel.class);
        var provider = SimpleModelProvider.of(model);

        assertThat(provider.getDefault()).isSameAs(model);
    }

    @Test
    void of_singleModel_getByNameThrowsIllegalArgument() {
        var model = mock(ChatModel.class);
        var provider = SimpleModelProvider.of(model);

        assertThatThrownBy(() -> provider.get("gpt-4o"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gpt-4o");
    }

    @Test
    void of_nullModel_throwsNullPointer() {
        assertThatThrownBy(() -> SimpleModelProvider.of((ChatModel) null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // of(String, ChatModel) factory
    // ========================

    @Test
    void of_namedModel_isAccessibleByNameAndAsDefault() {
        var model = mock(ChatModel.class);
        var provider = SimpleModelProvider.of("gpt-4o", model);

        assertThat(provider.get("gpt-4o")).isSameAs(model);
        assertThat(provider.getDefault()).isSameAs(model);
    }

    @Test
    void of_namedModel_unknownNameThrowsIllegalArgument() {
        var model = mock(ChatModel.class);
        var provider = SimpleModelProvider.of("gpt-4o", model);

        assertThatThrownBy(() -> provider.get("gpt-4o-mini"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gpt-4o-mini")
                .hasMessageContaining("gpt-4o");
    }

    @Test
    void of_namedModel_nullNameThrowsNullPointer() {
        assertThatThrownBy(() -> SimpleModelProvider.of(null, mock(ChatModel.class)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_namedModel_nullModelThrowsNullPointer() {
        assertThatThrownBy(() -> SimpleModelProvider.of("gpt-4o", null)).isInstanceOf(NullPointerException.class);
    }

    // ========================
    // builder
    // ========================

    @Test
    void builder_multipleModels_resolvesByName() {
        var cheap = mock(ChatModel.class);
        var premium = mock(ChatModel.class);

        var provider = SimpleModelProvider.builder()
                .model("cheap", cheap)
                .model("premium", premium)
                .defaultModel(cheap)
                .build();

        assertThat(provider.get("cheap")).isSameAs(cheap);
        assertThat(provider.get("premium")).isSameAs(premium);
        assertThat(provider.getDefault()).isSameAs(cheap);
    }

    @Test
    void builder_noDefaultConfigured_getDefaultThrowsIllegalState() {
        var provider = SimpleModelProvider.builder()
                .model("gpt-4o", mock(ChatModel.class))
                .build();

        assertThatThrownBy(provider::getDefault)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No default model");
    }

    @Test
    void builder_emptyBuild_getDefaultThrowsIllegalState() {
        var provider = SimpleModelProvider.builder().build();

        assertThatThrownBy(provider::getDefault).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void get_nullName_throwsNullPointer() {
        var provider = SimpleModelProvider.of(mock(ChatModel.class));

        assertThatThrownBy(() -> provider.get(null)).isInstanceOf(NullPointerException.class);
    }
}
