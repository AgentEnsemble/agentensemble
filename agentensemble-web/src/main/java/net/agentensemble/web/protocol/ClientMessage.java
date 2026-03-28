package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for all client-to-server WebSocket messages.
 *
 * <p>Client message types:
 * <ul>
 *   <li>{@link ReviewDecisionMessage} -- browser's response to a {@link ReviewRequestedMessage}</li>
 *   <li>{@link PingMessage} -- keepalive; server responds with {@link PongMessage}</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ReviewDecisionMessage.class, name = "review_decision"),
    @JsonSubTypes.Type(value = PingMessage.class, name = "ping"),
    @JsonSubTypes.Type(value = TaskRequestMessage.class, name = "task_request"),
    @JsonSubTypes.Type(value = ToolRequestMessage.class, name = "tool_request"),
})
public sealed interface ClientMessage
        permits ReviewDecisionMessage, PingMessage, TaskRequestMessage, ToolRequestMessage {}
