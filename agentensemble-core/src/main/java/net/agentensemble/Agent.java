package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.lang.reflect.Method;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.ratelimit.RateLimitedChatModel;
import net.agentensemble.tool.AgentTool;

/**
 * An AI agent with a defined role, goal, and optional tools.
 *
 * Agents are immutable value objects. Use the builder to construct instances.
 * All validation is performed at build time -- a successfully constructed Agent
 * is guaranteed to be in a valid state.
 *
 * Example:
 * <pre>
 * Agent researcher = Agent.builder()
 *     .role("Senior Research Analyst")
 *     .goal("Find the latest developments in AI")
 *     .background("You are a veteran researcher...")
 *     .llm(openAiModel)
 *     .build();
 * </pre>
 */
@Builder(toBuilder = true)
@Value
public class Agent {

    /** The agent's role/title. Used in prompts and logging. Required. */
    String role;

    /** The agent's primary objective. Included in the system prompt. Required. */
    String goal;

    /**
     * Background context for the agent persona. Included in the system prompt
     * when non-null and non-blank. Optional.
     */
    String background;

    /**
     * Tools available to this agent. Each entry must be either an
     * {@link AgentTool} instance or an object with
     * {@code @dev.langchain4j.agent.tool.Tool} annotated methods.
     * Default: empty list (agent uses pure LLM reasoning, no tool loop).
     */
    List<Object> tools;

    /** The LLM to use for this agent. Any LangChain4j ChatModel. Required. */
    ChatModel llm;

    /**
     * Optional streaming LLM for token-by-token generation of the final agent response.
     *
     * <p>When set, this model is used to stream the final response (the direct LLM-to-answer
     * path, i.e. when the agent has no tools). Tool-calling iterations remain non-streaming.
     * This field takes precedence over any task-level or ensemble-level streaming model.
     *
     * <p>Default: null (non-streaming; uses {@link #llm} for all calls).
     */
    StreamingChatModel streamingLlm;

    /**
     * Whether this agent may delegate subtasks to other agents.
     * Default: false. Reserved for Phase 2 (agent delegation).
     */
    boolean allowDelegation;

    /**
     * When true, prompts and LLM responses are logged at INFO level.
     * Default: false.
     */
    boolean verbose;

    /**
     * Maximum number of tool call iterations before the agent is forced to
     * produce a final answer. Prevents infinite tool-call loops.
     * Must be greater than zero. Default: 25.
     */
    int maxIterations;

    /**
     * Optional formatting instructions appended to the system prompt.
     * Example: "Always respond in bullet points."
     * Default: empty string (omitted from prompt).
     */
    String responseFormat;

    /**
     * Custom builder that sets defaults and validates the Agent configuration
     * before constructing the immutable instance.
     *
     * Field initializers here define the default values (Lombok respects these
     * when it generates the builder methods).
     */
    public static class AgentBuilder {

        // Default values -- declared here so Lombok does not overwrite them
        private List<Object> tools = List.of();
        private boolean allowDelegation = false;
        private boolean verbose = false;
        private int maxIterations = 25;
        private String responseFormat = "";

        /**
         * Builder-only field: when set, the LLM is automatically wrapped with
         * {@link net.agentensemble.ratelimit.RateLimitedChatModel} at build time using the
         * default 30-second wait timeout.
         * This field is not stored on the resulting {@link Agent}; only the wrapped LLM is.
         */
        private RateLimit rateLimit = null;

        /**
         * Convenience method to apply a request rate limit to this agent's LLM.
         *
         * <p>At build time, the {@code llm} model is wrapped with a
         * {@link net.agentensemble.ratelimit.RateLimitedChatModel} using the supplied
         * {@code rateLimit} and the default wait timeout of
         * {@link net.agentensemble.ratelimit.RateLimitedChatModel#DEFAULT_WAIT_TIMEOUT}.
         *
         * <p>To share a single rate-limit bucket between multiple agents, pass a pre-configured
         * {@link net.agentensemble.ratelimit.RateLimitedChatModel} directly to
         * {@code llm()} instead.
         *
         * @param rateLimit the rate limit to enforce; must not be null
         * @return this builder
         */
        public AgentBuilder rateLimit(RateLimit rateLimit) {
            this.rateLimit = rateLimit;
            return this;
        }

        public Agent build() {
            validateRole();
            validateGoal();
            validateLlm();
            applyRateLimit();
            validateMaxIterations();
            validateTools();
            tools = List.copyOf(tools);
            // Normalize responseFormat: null is treated as empty string to avoid NPE in prompt building
            if (responseFormat == null) {
                responseFormat = "";
            }
            return new Agent(
                    role,
                    goal,
                    background,
                    tools,
                    llm,
                    streamingLlm,
                    allowDelegation,
                    verbose,
                    maxIterations,
                    responseFormat);
        }

        private void applyRateLimit() {
            if (rateLimit != null) {
                llm = RateLimitedChatModel.of(llm, rateLimit);
            }
        }

        private void validateRole() {
            if (role == null || role.isBlank()) {
                throw new ValidationException("Agent role must not be blank");
            }
        }

        private void validateGoal() {
            if (goal == null || goal.isBlank()) {
                throw new ValidationException("Agent goal must not be blank");
            }
        }

        private void validateLlm() {
            if (llm == null) {
                throw new ValidationException("Agent LLM must not be null");
            }
        }

        private void validateMaxIterations() {
            if (maxIterations <= 0) {
                throw new ValidationException("Agent maxIterations must be > 0, got: " + maxIterations);
            }
        }

        private void validateTools() {
            if (tools == null) {
                tools = List.of();
                return;
            }
            for (int i = 0; i < tools.size(); i++) {
                Object tool = tools.get(i);
                if (tool == null) {
                    throw new ValidationException("Tool at index " + i + " must not be null");
                }
                if (!(tool instanceof AgentTool) && !hasToolAnnotatedMethods(tool)) {
                    throw new ValidationException(
                            "Tool at index " + i + " (" + tool.getClass().getSimpleName()
                                    + ") is neither an AgentTool nor has @Tool-annotated methods");
                }
            }
        }

        private static boolean hasToolAnnotatedMethods(Object obj) {
            for (Method method : obj.getClass().getMethods()) {
                if (method.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class)) {
                    return true;
                }
            }
            return false;
        }
    }
}
