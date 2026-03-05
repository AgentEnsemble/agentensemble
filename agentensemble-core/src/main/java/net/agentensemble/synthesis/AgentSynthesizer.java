package net.agentensemble.synthesis;

import net.agentensemble.Agent;
import net.agentensemble.Task;

/**
 * SPI for synthesizing an {@link Agent} from a {@link Task} description.
 *
 * <p>When no explicit agent is declared on a task, the framework invokes an
 * {@code AgentSynthesizer} to produce one automatically before execution.
 *
 * <p>Two built-in implementations are provided:
 * <ul>
 *   <li>{@link #template()} -- derives role, goal, and backstory from the task description
 *       using a verb-to-role lookup table. Deterministic and requires no extra LLM call.</li>
 *   <li>{@link #llmBased()} -- invokes the LLM once to generate an optimal persona.
 *       Non-deterministic but potentially higher quality.</li>
 * </ul>
 *
 * <p>The active synthesizer is configured on
 * {@code Ensemble.builder().agentSynthesizer(AgentSynthesizer)}.
 * The default is {@link #template()}.
 *
 * <pre>
 * // Zero-ceremony: the framework synthesizes an agent from the task description
 * EnsembleOutput result = Ensemble.run(model,
 *     Task.of("Research the latest AI trends and summarise findings"));
 *
 * // LLM-based synthesis for higher quality personas
 * Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .agentSynthesizer(AgentSynthesizer.llmBased())
 *     .task(Task.of("Research the latest AI trends"))
 *     .build()
 *     .run();
 * </pre>
 */
public interface AgentSynthesizer {

    /**
     * Synthesize an agent from the task description and synthesis context.
     *
     * <p>Implementations must return a valid, fully-configured {@link Agent}
     * (role, goal, and LLM are required). The {@link SynthesisContext#model()} is
     * used as the agent's LLM unless the implementation substitutes a different one.
     *
     * @param task    the task that requires an agent; never null
     * @param context context bundling the resolved LLM and locale; never null
     * @return a synthesized agent; never null
     */
    Agent synthesize(Task task, SynthesisContext context);

    /**
     * Return the default template-based synthesizer.
     *
     * <p>Derives role, goal, and backstory from the task description using
     * a verb-to-role lookup table. Deterministic: the same task description
     * always produces the same agent. No extra LLM call.
     *
     * @return the template-based synthesizer
     */
    static AgentSynthesizer template() {
        return new TemplateAgentSynthesizer();
    }

    /**
     * Return the LLM-based synthesizer.
     *
     * <p>Invokes the LLM once per task to generate an optimal persona (role,
     * goal, backstory). Non-deterministic but may produce higher-quality agent
     * descriptions. Incurs one additional LLM call per task that uses this
     * synthesizer.
     *
     * @return the LLM-based synthesizer
     */
    static AgentSynthesizer llmBased() {
        return new LlmBasedAgentSynthesizer();
    }
}
