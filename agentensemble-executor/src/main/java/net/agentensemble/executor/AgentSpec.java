package net.agentensemble.executor;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Specification for configuring an agent within a {@link TaskRequest}.
 *
 * <p>When an {@code AgentSpec} is provided, {@link TaskExecutor} constructs an explicit
 * {@link net.agentensemble.Agent} with the given role, goal, and tool set, rather than
 * auto-synthesizing one from the task description.
 *
 * <p>All fields except {@code role} and {@code goal} are optional:
 * <ul>
 *   <li>{@code background} -- optional persona context injected into the agent's system prompt</li>
 *   <li>{@code toolNames} -- names resolved by the {@link ToolProvider} registered on the executor</li>
 *   <li>{@code maxIterations} -- overrides the default ReAct loop iteration limit</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>
 * AgentSpec researcher = AgentSpec.builder()
 *     .role("Research Analyst")
 *     .goal("Find accurate, comprehensive information on any topic")
 *     .background("You are a meticulous researcher with access to web search and date tools")
 *     .toolNames(List.of("web-search", "datetime"))
 *     .maxIterations(15)
 *     .build();
 *
 * // Or with the convenience factory:
 * AgentSpec writer = AgentSpec.of("Technical Writer", "Write clear, engaging documentation");
 * </pre>
 */
@Value
@Builder
public class AgentSpec {

    /** Role label for the agent (e.g., "Research Analyst", "Technical Writer"). Required. */
    String role;

    /** Primary objective for the agent (e.g., "Find accurate, comprehensive information"). Required. */
    String goal;

    /** Optional background context injected into the agent's system prompt. */
    String background;

    /**
     * Tool names to equip the agent with. Each name is resolved by the {@link ToolProvider}
     * registered on the executor. A null or empty list produces an agent with no tools.
     */
    List<String> toolNames;

    /**
     * Maximum number of ReAct loop iterations. When null, the framework default (25) is used.
     */
    Integer maxIterations;

    /**
     * Convenience factory: creates an {@code AgentSpec} with role and goal, no tools or background.
     *
     * @param role the agent role
     * @param goal the agent goal
     * @return a minimal agent spec
     */
    public static AgentSpec of(String role, String goal) {
        return builder().role(role).goal(goal).build();
    }

    /**
     * Convenience factory: creates an {@code AgentSpec} with role, goal, and background.
     *
     * @param role       the agent role
     * @param goal       the agent goal
     * @param background the agent background context
     * @return an agent spec with background
     */
    public static AgentSpec of(String role, String goal, String background) {
        return builder().role(role).goal(goal).background(background).build();
    }
}
