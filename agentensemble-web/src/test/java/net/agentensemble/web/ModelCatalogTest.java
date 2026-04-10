package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModelCatalog}: builder validation, resolution, streaming, listing.
 */
class ModelCatalogTest {

    private ChatModel sonnetModel;
    private ChatModel opusModel;
    private StreamingChatModel streamingSonnet;

    @BeforeEach
    void setUp() {
        sonnetModel = mock(ChatModel.class);
        opusModel = mock(ChatModel.class);
        streamingSonnet = mock(StreamingChatModel.class);
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void builder_nullAlias_throws() {
        assertThatThrownBy(() -> ModelCatalog.builder().model(null, sonnetModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model alias must not be null or blank");
    }

    @Test
    void builder_blankAlias_throws() {
        assertThatThrownBy(() -> ModelCatalog.builder().model("", sonnetModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model alias must not be null or blank");
    }

    @Test
    void builder_nullModel_throws() {
        assertThatThrownBy(() -> ModelCatalog.builder().model("sonnet", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model must not be null");
    }

    @Test
    void builder_duplicateAlias_throws() {
        assertThatThrownBy(() ->
                        ModelCatalog.builder().model("sonnet", sonnetModel).model("sonnet", opusModel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate model alias: 'sonnet'");
    }

    @Test
    void builder_nullStreamingModel_throws() {
        assertThatThrownBy(() -> ModelCatalog.builder().model("sonnet", sonnetModel, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("streamingModel must not be null");
    }

    @Test
    void builder_emptyBuild_producesEmptyCatalog() {
        ModelCatalog catalog = ModelCatalog.builder().build();
        assertThat(catalog.size()).isZero();
        assertThat(catalog.list()).isEmpty();
    }

    // ========================
    // resolve
    // ========================

    @Test
    void resolve_knownAlias_returnsCorrectModel() {
        ModelCatalog catalog = ModelCatalog.builder()
                .model("sonnet", sonnetModel)
                .model("opus", opusModel)
                .build();

        assertThat(catalog.resolve("sonnet")).isSameAs(sonnetModel);
        assertThat(catalog.resolve("opus")).isSameAs(opusModel);
    }

    @Test
    void resolve_unknownAlias_throwsWithHelpfulMessage() {
        ModelCatalog catalog =
                ModelCatalog.builder().model("sonnet", sonnetModel).build();

        assertThatThrownBy(() -> catalog.resolve("gpt-4"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Unknown model 'gpt-4'")
                .hasMessageContaining("sonnet");
    }

    // ========================
    // find
    // ========================

    @Test
    void find_knownAlias_returnsNonEmpty() {
        ModelCatalog catalog =
                ModelCatalog.builder().model("sonnet", sonnetModel).build();
        Optional<ChatModel> found = catalog.find("sonnet");
        assertThat(found).isPresent().contains(sonnetModel);
    }

    @Test
    void find_unknownAlias_returnsEmpty() {
        ModelCatalog catalog =
                ModelCatalog.builder().model("sonnet", sonnetModel).build();
        assertThat(catalog.find("unknown")).isEmpty();
    }

    // ========================
    // resolveStreaming
    // ========================

    @Test
    void resolveStreaming_whenRegistered_returnsStreamingModel() {
        ModelCatalog catalog = ModelCatalog.builder()
                .model("sonnet", sonnetModel, streamingSonnet)
                .build();

        assertThat(catalog.resolveStreaming("sonnet")).isSameAs(streamingSonnet);
    }

    @Test
    void resolveStreaming_whenNotRegistered_returnsNull() {
        ModelCatalog catalog =
                ModelCatalog.builder().model("sonnet", sonnetModel).build();
        assertThat(catalog.resolveStreaming("sonnet")).isNull();
    }

    @Test
    void resolveStreaming_unknownAlias_returnsNull() {
        ModelCatalog catalog =
                ModelCatalog.builder().model("sonnet", sonnetModel).build();
        assertThat(catalog.resolveStreaming("unknown")).isNull();
    }

    // ========================
    // list
    // ========================

    @Test
    void list_returnsModelInfosInInsertionOrder() {
        ModelCatalog catalog = ModelCatalog.builder()
                .model("sonnet", sonnetModel)
                .model("opus", opusModel)
                .build();

        List<ModelCatalog.ModelInfo> infos = catalog.list();
        assertThat(infos).hasSize(2);
        assertThat(infos.get(0).alias()).isEqualTo("sonnet");
        assertThat(infos.get(1).alias()).isEqualTo("opus");
    }

    // ========================
    // deriveProvider
    // ========================

    @Test
    void deriveProvider_unknownClass_returnsUnknown() {
        // Our mock class doesn't contain known provider names
        assertThat(ModelCatalog.deriveProvider(sonnetModel)).isEqualTo("unknown");
    }

    @Test
    void deriveProvider_openAiClass_returnsOpenai() {
        ChatModel openAiModel = new OpenAiChatModel();
        assertThat(ModelCatalog.deriveProvider(openAiModel)).isEqualTo("openai");
    }

    @Test
    void deriveProvider_anthropicClass_returnsAnthropic() {
        ChatModel anthropicModel = new AnthropicChatModel();
        assertThat(ModelCatalog.deriveProvider(anthropicModel)).isEqualTo("anthropic");
    }

    @Test
    void deriveProvider_geminiClass_returnsGoogle() {
        assertThat(ModelCatalog.deriveProvider(new GeminiChatModel())).isEqualTo("google");
    }

    @Test
    void deriveProvider_vertexAiClass_returnsGoogle() {
        assertThat(ModelCatalog.deriveProvider(new VertexaiChatModel())).isEqualTo("google");
    }

    @Test
    void deriveProvider_googleClass_returnsGoogle() {
        assertThat(ModelCatalog.deriveProvider(new GoogleChatModel())).isEqualTo("google");
    }

    @Test
    void deriveProvider_mistralClass_returnsMistral() {
        assertThat(ModelCatalog.deriveProvider(new MistralChatModel())).isEqualTo("mistral");
    }

    @Test
    void deriveProvider_ollamaClass_returnsOllama() {
        assertThat(ModelCatalog.deriveProvider(new OllamaChatModel())).isEqualTo("ollama");
    }

    @Test
    void deriveProvider_cohereClass_returnsCohere() {
        assertThat(ModelCatalog.deriveProvider(new CohereChatModel())).isEqualTo("cohere");
    }

    @Test
    void deriveProvider_azureClass_returnsAzure() {
        assertThat(ModelCatalog.deriveProvider(new AzureChatModel())).isEqualTo("azure");
    }

    @Test
    void deriveProvider_bedrockClass_returnsBedrock() {
        assertThat(ModelCatalog.deriveProvider(new BedrockChatModel())).isEqualTo("bedrock");
    }

    @Test
    void deriveProvider_huggingfaceClass_returnsHuggingface() {
        assertThat(ModelCatalog.deriveProvider(new HuggingfaceChatModel())).isEqualTo("huggingface");
    }

    // ========================
    // size
    // ========================

    @Test
    void size_reflectsRegisteredModelCount() {
        ModelCatalog catalog = ModelCatalog.builder()
                .model("sonnet", sonnetModel)
                .model("opus", opusModel)
                .build();
        assertThat(catalog.size()).isEqualTo(2);
    }

    // ========================
    // Helper stub classes for provider detection tests
    // ========================

    /**
     * Stub whose class simple name contains "OpenAi" -- used to verify provider detection.
     * ChatModel in LangChain4j 1.12.2 has only default methods, so no overrides required.
     */
    private static final class OpenAiChatModel implements ChatModel {}

    /** Stub whose class name contains "Anthropic". */
    private static final class AnthropicChatModel implements ChatModel {}

    /** Stub whose class name contains "Gemini". */
    private static final class GeminiChatModel implements ChatModel {}

    /** Stub whose class name contains "Vertexai". */
    private static final class VertexaiChatModel implements ChatModel {}

    /** Stub whose class name contains "Google". */
    private static final class GoogleChatModel implements ChatModel {}

    /** Stub whose class name contains "Mistral". */
    private static final class MistralChatModel implements ChatModel {}

    /** Stub whose class name contains "Ollama". */
    private static final class OllamaChatModel implements ChatModel {}

    /** Stub whose class name contains "Cohere". */
    private static final class CohereChatModel implements ChatModel {}

    /** Stub whose class name contains "Azure". */
    private static final class AzureChatModel implements ChatModel {}

    /** Stub whose class name contains "Bedrock". */
    private static final class BedrockChatModel implements ChatModel {}

    /** Stub whose class name contains "Huggingface". */
    private static final class HuggingfaceChatModel implements ChatModel {}
}
