package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface for all server-to-client WebSocket messages in the live dashboard wire protocol.
 *
 * <p>Each permitted implementation is a Java record that serializes to JSON with a {@code type}
 * discriminator field. Jackson uses {@link JsonTypeInfo} to include the discriminator on
 * serialization and resolve the correct type on deserialization.
 *
 * <p>Server-to-client message types:
 * <ul>
 *   <li>{@link HelloMessage} -- sent when a client connects; contains current trace snapshot</li>
 *   <li>{@link EnsembleStartedMessage} -- sent when ensemble execution begins</li>
 *   <li>{@link TaskStartedMessage} -- mirrors {@code TaskStartEvent}</li>
 *   <li>{@link TaskCompletedMessage} -- mirrors {@code TaskCompleteEvent}</li>
 *   <li>{@link TaskFailedMessage} -- mirrors {@code TaskFailedEvent}</li>
 *   <li>{@link ToolCalledMessage} -- mirrors {@code ToolCallEvent}</li>
 *   <li>{@link DelegationStartedMessage} -- mirrors {@code DelegationStartedEvent}</li>
 *   <li>{@link DelegationCompletedMessage} -- mirrors {@code DelegationCompletedEvent}</li>
 *   <li>{@link DelegationFailedMessage} -- mirrors {@code DelegationFailedEvent}</li>
 *   <li>{@link ReviewRequestedMessage} -- sent when a review gate fires</li>
 *   <li>{@link ReviewTimedOutMessage} -- sent when a review times out</li>
 *   <li>{@link EnsembleCompletedMessage} -- sent when ensemble execution ends</li>
 *   <li>{@link HeartbeatMessage} -- sent every 15 seconds to keep connections alive</li>
 *   <li>{@link PongMessage} -- sent in response to a client {@link PingMessage}</li>
 *   <li>{@link TokenMessage} -- sent for each token during streaming final response generation</li>
 *   <li>{@link FileChangedMessage} -- sent when a coding tool modifies a file</li>
 *   <li>{@link MetricsSnapshotMessage} -- sent with cumulative agent metrics after each LLM iteration</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = HelloMessage.class, name = "hello"),
    @JsonSubTypes.Type(value = EnsembleStartedMessage.class, name = "ensemble_started"),
    @JsonSubTypes.Type(value = TaskStartedMessage.class, name = "task_started"),
    @JsonSubTypes.Type(value = TaskCompletedMessage.class, name = "task_completed"),
    @JsonSubTypes.Type(value = TaskFailedMessage.class, name = "task_failed"),
    @JsonSubTypes.Type(value = ToolCalledMessage.class, name = "tool_called"),
    @JsonSubTypes.Type(value = DelegationStartedMessage.class, name = "delegation_started"),
    @JsonSubTypes.Type(value = DelegationCompletedMessage.class, name = "delegation_completed"),
    @JsonSubTypes.Type(value = DelegationFailedMessage.class, name = "delegation_failed"),
    @JsonSubTypes.Type(value = ReviewRequestedMessage.class, name = "review_requested"),
    @JsonSubTypes.Type(value = ReviewTimedOutMessage.class, name = "review_timed_out"),
    @JsonSubTypes.Type(value = EnsembleCompletedMessage.class, name = "ensemble_completed"),
    @JsonSubTypes.Type(value = HeartbeatMessage.class, name = "heartbeat"),
    @JsonSubTypes.Type(value = PongMessage.class, name = "pong"),
    @JsonSubTypes.Type(value = TokenMessage.class, name = "token"),
    @JsonSubTypes.Type(value = TaskAcceptedMessage.class, name = "task_accepted"),
    @JsonSubTypes.Type(value = TaskProgressMessage.class, name = "task_progress"),
    @JsonSubTypes.Type(value = TaskResponseMessage.class, name = "task_response"),
    @JsonSubTypes.Type(value = ToolResponseMessage.class, name = "tool_response"),
    @JsonSubTypes.Type(value = DirectiveAckMessage.class, name = "directive_ack"),
    @JsonSubTypes.Type(value = DirectiveActiveMessage.class, name = "directive_active"),
    @JsonSubTypes.Type(value = CapabilityResponseMessage.class, name = "capability_response"),
    @JsonSubTypes.Type(value = CapacityUpdateMessage.class, name = "capacity_update"),
    @JsonSubTypes.Type(value = ProfileAppliedMessage.class, name = "profile_applied"),
    @JsonSubTypes.Type(value = LlmIterationStartedMessage.class, name = "llm_iteration_started"),
    @JsonSubTypes.Type(value = LlmIterationCompletedMessage.class, name = "llm_iteration_completed"),
    @JsonSubTypes.Type(value = FileChangedMessage.class, name = "file_changed"),
    @JsonSubTypes.Type(value = MetricsSnapshotMessage.class, name = "metrics_snapshot"),
})
public sealed interface ServerMessage
        permits HelloMessage,
                EnsembleStartedMessage,
                TaskStartedMessage,
                TaskCompletedMessage,
                TaskFailedMessage,
                ToolCalledMessage,
                DelegationStartedMessage,
                DelegationCompletedMessage,
                DelegationFailedMessage,
                ReviewRequestedMessage,
                ReviewTimedOutMessage,
                EnsembleCompletedMessage,
                HeartbeatMessage,
                PongMessage,
                TokenMessage,
                TaskAcceptedMessage,
                TaskProgressMessage,
                TaskResponseMessage,
                ToolResponseMessage,
                DirectiveAckMessage,
                DirectiveActiveMessage,
                CapabilityResponseMessage,
                CapacityUpdateMessage,
                ProfileAppliedMessage,
                LlmIterationStartedMessage,
                LlmIterationCompletedMessage,
                FileChangedMessage,
                MetricsSnapshotMessage {}
