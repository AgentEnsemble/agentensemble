package net.agentensemble.ensemble;

/**
 * Functional interface for broadcasting scheduled task results to a named topic.
 *
 * <p>In simple mode, this may log the result. In production mode, this would
 * publish to a Kafka topic or similar message broker.
 *
 * @see ScheduledTask#broadcastTo()
 */
@FunctionalInterface
public interface BroadcastHandler {
    /**
     * Broadcast a result string to the named topic.
     *
     * @param topic  the topic name; must not be null
     * @param result the result string; must not be null
     */
    void broadcast(String topic, String result);
}
