/**
 * Redis-backed transport implementations for the AgentEnsemble Ensemble Network.
 *
 * <p>This module provides durable {@link net.agentensemble.network.transport.RequestQueue}
 * and {@link net.agentensemble.network.transport.ResultStore} implementations backed by
 * Redis Streams and Redis key-value storage respectively.
 *
 * <ul>
 *   <li>{@link net.agentensemble.transport.redis.RedisRequestQueue} -- Redis Streams with
 *       consumer groups for durable, horizontally-scalable work request delivery</li>
 *   <li>{@link net.agentensemble.transport.redis.RedisResultStore} -- Redis key-value with
 *       TTL and Pub/Sub for shared result storage and notification</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * Transport transport = Transport.durable(
 *     "kitchen",
 *     RedisRequestQueue.create(redisClient),
 *     RedisResultStore.create(redisClient));
 * </pre>
 *
 * @see net.agentensemble.network.transport.Transport#durable(String,
 *      net.agentensemble.network.transport.RequestQueue,
 *      net.agentensemble.network.transport.ResultStore)
 */
package net.agentensemble.transport.redis;
