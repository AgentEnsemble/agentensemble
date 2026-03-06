package net.agentensemble.callback;

/**
 * Event fired for each token received during streaming generation of the final agent response.
 *
 * <p>This event is only fired when a {@code dev.langchain4j.model.chat.StreamingChatModel} is
 * resolved for the executing agent. Resolution order (first non-null wins):
 * <ol>
 *   <li>{@code Agent.builder().streamingLlm(model)}</li>
 *   <li>{@code Task.builder().streamingChatLanguageModel(model)}</li>
 *   <li>{@code Ensemble.builder().streamingChatLanguageModel(model)}</li>
 * </ol>
 *
 * <p>Token events are fired during the direct LLM-to-answer path (when the agent has no tools,
 * or more precisely when {@code executeWithoutTools} is used). Tool-loop iterations remain
 * non-streaming because the full response must be inspected to detect tool-call requests.
 *
 * <p>Thread safety: in a parallel workflow, token events for different agents may be fired
 * concurrently from different virtual threads. Listener implementations must be thread-safe.
 *
 * <p>The combination of {@link #agentRole()} and {@link #taskDescription()} uniquely identifies
 * the task in parallel workflows, where multiple tasks may share the same agent role.
 *
 * @param token           the text fragment emitted by the streaming model
 * @param agentRole       the role of the agent generating the response
 * @param taskDescription the description of the task being executed; combined with
 *                        {@code agentRole} this uniquely identifies the task in parallel workflows
 */
public record TokenEvent(String token, String agentRole, String taskDescription) {}
