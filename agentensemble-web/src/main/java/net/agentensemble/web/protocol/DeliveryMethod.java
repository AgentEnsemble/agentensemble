package net.agentensemble.web.protocol;

/**
 * Transport method for delivering a work request result back to the requester.
 *
 * @see DeliverySpec
 * @see WorkRequest
 */
public enum DeliveryMethod {

    /** Direct, real-time delivery over WebSocket. */
    WEBSOCKET,

    /** Durable point-to-point queue (e.g., Redis Streams, SQS). */
    QUEUE,

    /** Durable pub/sub topic (e.g., Kafka). */
    TOPIC,

    /** HTTP POST callback to a URL. */
    WEBHOOK,

    /** Write to a shared result store; requester polls or subscribes. */
    STORE,

    /** Offer to all replicas; first to claim receives the payload. */
    BROADCAST_CLAIM,

    /** Fire and forget -- no result delivery. */
    NONE
}
