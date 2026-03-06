package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonTypeName;
import java.time.Instant;

/**
 * Sent for each token received during streaming generation of the final agent response.
 *
 * <p>These messages are emitted by {@link net.agentensemble.web.WebSocketStreamingListener}
 * when a {@code StreamingChatModel} is configured for an agent and that agent is executing
 * the direct LLM-to-answer path (no tool loop). Token messages are not added to the
 * late-join snapshot because they are ephemeral; the {@link TaskCompletedMessage} carries
 * the authoritative final output.
 *
 * <p>The combination of {@link #agentRole()} and {@link #taskDescription()} uniquely
 * identifies the task in parallel workflows, where multiple tasks may share the same
 * agent role.
 *
 * @param token           the text fragment emitted by the streaming model
 * @param agentRole       the role of the agent generating the response
 * @param taskDescription the description of the task being executed
 * @param sentAt          server-side timestamp when this message was sent
 */
@JsonTypeName("token")
public record TokenMessage(String token, String agentRole, String taskDescription, Instant sentAt)
        implements ServerMessage {}
