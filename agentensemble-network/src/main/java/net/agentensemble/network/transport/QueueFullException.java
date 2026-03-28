package net.agentensemble.network.transport;

/**
 * Thrown when a {@link PriorityWorkQueue} rejects an enqueue because the queue has
 * reached its configured capacity.
 *
 * @see PriorityWorkQueue
 */
public class QueueFullException extends RuntimeException {

    private final String queueName;
    private final int capacity;

    /**
     * @param queueName the queue that rejected the request
     * @param capacity  the configured maximum capacity
     */
    public QueueFullException(String queueName, int capacity) {
        super("Queue '" + queueName + "' is at capacity (" + capacity + ")");
        this.queueName = queueName;
        this.capacity = capacity;
    }

    /** Returns the name of the queue that rejected the enqueue. */
    public String getQueueName() {
        return queueName;
    }

    /** Returns the configured maximum capacity. */
    public int getCapacity() {
        return capacity;
    }
}
