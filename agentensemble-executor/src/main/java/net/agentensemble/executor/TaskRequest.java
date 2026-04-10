package net.agentensemble.executor;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * A request to execute a single AgentEnsemble task via {@link TaskExecutor}.
 *
 * <p>Each {@code TaskRequest} maps to exactly one external-workflow activity (e.g., a Temporal
 * activity). The workflow engine is responsible for sequencing activities and passing upstream
 * outputs via {@code getContext()}.
 *
 * <h2>Context passing between tasks</h2>
 *
 * <p>Outputs from upstream tasks are injected into this task's execution as template variable
 * inputs. For example, if an upstream task's output is stored under the key {@code "research"},
 * the task description may reference it as {@code {research}}:
 *
 * <pre>
 * TaskRequest.builder()
 *     .description("Write an article about {topic} based on: {research}")
 *     .expectedOutput("A polished 500-word article")
 *     .agent(AgentSpec.of("Writer", "Write compelling, accurate content"))
 *     .context(Map.of("research", researchResult.output()))
 *     .inputs(Map.of("topic", "Artificial Intelligence"))
 *     .build();
 * </pre>
 *
 * <p>Context entries and explicit inputs are merged before execution. Explicit
 * {@code inputs} entries take precedence over {@code context} entries when both share a key.
 *
 * <h2>Agent auto-synthesis</h2>
 *
 * <p>When {@code getAgent()} is null, AgentEnsemble auto-synthesizes an agent persona
 * from the task description and expected output. When {@code getAgent()} is set, the
 * specified agent is used as-is.
 */
@Value
@Builder
public class TaskRequest {

    /**
     * The task description. Supports {@code {variable}} template placeholders resolved from
     * {@link #getInputs()} and {@code getContext()}.
     */
    String description;

    /**
     * What the agent should produce. Supports {@code {variable}} template placeholders.
     * Optional; the ensemble still executes without an expected output specification.
     */
    String expectedOutput;

    /**
     * Optional agent specification. When null, AgentEnsemble auto-synthesizes an agent
     * persona from the task description and expected output.
     */
    AgentSpec agent;

    /**
     * Outputs from upstream tasks, keyed by label. Each entry is injected as a template
     * variable so that {@code {label}} in the task description resolves to the upstream output.
     *
     * <p>May be null or empty when this is the first task in the pipeline.
     */
    Map<String, String> context;

    /**
     * Explicit template variable values for {@code {variable}} placeholders in the task
     * description and expected output. These take precedence over {@code getContext()}
     * entries when both share a key.
     *
     * <p>May be null or empty when no template substitution is needed.
     */
    Map<String, String> inputs;

    /**
     * Optional model name resolved by the {@link ModelProvider} on the executor. When null,
     * the provider's default model is used.
     */
    String modelName;

    /**
     * Convenience factory: creates a minimal request with only a description.
     *
     * <p>When {@code expectedOutput} is null, both {@link TaskExecutor} and
     * {@link EnsembleExecutor} substitute {@code Task.DEFAULT_EXPECTED_OUTPUT}
     * ({@value net.agentensemble.Task#DEFAULT_EXPECTED_OUTPUT}) when building the
     * underlying core task, so this factory always produces an executable request.
     *
     * @param description the task description
     * @return a request with no agent spec, context, inputs, or model override
     */
    public static TaskRequest of(String description) {
        return builder().description(description).build();
    }

    /**
     * Convenience factory: creates a request with a description and expected output.
     *
     * @param description    the task description
     * @param expectedOutput what the agent should produce
     * @return a request with no agent spec, context, inputs, or model override
     */
    public static TaskRequest of(String description, String expectedOutput) {
        return builder().description(description).expectedOutput(expectedOutput).build();
    }
}
