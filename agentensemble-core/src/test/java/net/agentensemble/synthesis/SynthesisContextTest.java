package net.agentensemble.synthesis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SynthesisContext}.
 */
class SynthesisContextTest {

    @Test
    void of_setsModelAndDefaultLocale() {
        ChatModel model = mock(ChatModel.class);

        SynthesisContext ctx = SynthesisContext.of(model);

        assertThat(ctx.model()).isSameAs(model);
        assertThat(ctx.locale()).isEqualTo(Locale.getDefault());
    }

    @Test
    void constructor_setsModelAndLocale() {
        ChatModel model = mock(ChatModel.class);
        Locale locale = Locale.FRENCH;

        SynthesisContext ctx = new SynthesisContext(model, locale);

        assertThat(ctx.model()).isSameAs(model);
        assertThat(ctx.locale()).isEqualTo(locale);
    }

    @Test
    void equalContexts_areEqual() {
        ChatModel model = mock(ChatModel.class);
        Locale locale = Locale.ENGLISH;

        SynthesisContext ctx1 = new SynthesisContext(model, locale);
        SynthesisContext ctx2 = new SynthesisContext(model, locale);

        assertThat(ctx1).isEqualTo(ctx2);
    }
}
