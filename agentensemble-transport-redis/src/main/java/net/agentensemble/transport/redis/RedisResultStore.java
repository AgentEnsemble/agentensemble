package net.agentensemble.transport.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.network.transport.ResultStore;
import net.agentensemble.web.protocol.WorkResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed implementation of {@link ResultStore}.
 *
 * <p>Stores work responses as JSON strings in Redis with TTL-based automatic expiration.
 * Uses Redis Pub/Sub for {@link #subscribe} notifications when results are stored.
 *
 * <h2>Redis key scheme</h2>
 * <ul>
 *   <li>Result storage: {@code agentensemble:result:{requestId}} (string with TTL)</li>
 *   <li>Pub/Sub notification: {@code agentensemble:notify:{requestId}} (channel)</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * RedisResultStore store = RedisResultStore.create(client);
 *
 * store.store("req-1", response, Duration.ofHours(1));
 * WorkResponse result = store.retrieve("req-1");
 * </pre>
 *
 * <p>Thread-safe.
 *
 * @see ResultStore
 * @see RedisRequestQueue
 */
public final class RedisResultStore implements ResultStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisResultStore.class);

    static final String KEY_PREFIX = "agentensemble:result:";
    static final String NOTIFY_PREFIX = "agentensemble:notify:";

    private final StatefulRedisConnection<String, String> connection;
    private final StatefulRedisPubSubConnection<String, String> pubSubConnection;
    private final RedisCodec codec;

    private RedisResultStore(RedisClient client) {
        Objects.requireNonNull(client, "client must not be null");
        this.connection = client.connect();
        this.pubSubConnection = client.connectPubSub();
        this.codec = new RedisCodec();
    }

    /**
     * Create a Redis result store.
     *
     * @param client the Lettuce Redis client; must not be null
     * @return a new {@link RedisResultStore}
     */
    public static RedisResultStore create(RedisClient client) {
        return new RedisResultStore(client);
    }

    @Override
    public void store(String requestId, WorkResponse response, Duration ttl) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");

        String json = codec.serialize(response);
        RedisCommands<String, String> commands = connection.sync();

        // Store with TTL
        commands.set(resultKey(requestId), json, SetArgs.Builder.ex(ttl));

        // Notify subscribers
        commands.publish(notifyChannel(requestId), json);
    }

    @Override
    public WorkResponse retrieve(String requestId) {
        Objects.requireNonNull(requestId, "requestId must not be null");

        RedisCommands<String, String> commands = connection.sync();
        String json = commands.get(resultKey(requestId));

        if (json == null) {
            return null;
        }
        return codec.deserialize(json, WorkResponse.class);
    }

    @Override
    public void subscribe(String requestId, Consumer<WorkResponse> callback) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        String channel = notifyChannel(requestId);

        // Add listener for this channel
        pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String ch, String message) {
                if (channel.equals(ch)) {
                    try {
                        WorkResponse response = codec.deserialize(message, WorkResponse.class);
                        callback.accept(response);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to deserialize result notification for requestId={}: {}",
                                requestId,
                                e.getMessage(),
                                e);
                    }
                }
            }
        });

        pubSubConnection.sync().subscribe(channel);

        // Race condition mitigation: check if the result was stored before we subscribed
        WorkResponse existing = retrieve(requestId);
        if (existing != null) {
            callback.accept(existing);
        }
    }

    @Override
    public void close() {
        pubSubConnection.close();
        connection.close();
    }

    // ========================
    // Internal
    // ========================

    static String resultKey(String requestId) {
        return KEY_PREFIX + requestId;
    }

    static String notifyChannel(String requestId) {
        return NOTIFY_PREFIX + requestId;
    }
}
