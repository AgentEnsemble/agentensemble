package net.agentensemble.synthesis;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Locale;

/**
 * Context passed to an {@link AgentSynthesizer} when creating an agent for a task.
 *
 * @param model  the LLM to use for the synthesized agent (and optionally for LLM-based synthesis)
 * @param locale the locale used to tune language generation; typically {@link Locale#getDefault()}
 */
public record SynthesisContext(ChatModel model, Locale locale) {

    /**
     * Create a context with the given model using the default locale.
     *
     * @param model the LLM to use; must not be null
     * @return a new SynthesisContext
     */
    public static SynthesisContext of(ChatModel model) {
        return new SynthesisContext(model, Locale.getDefault());
    }
}
