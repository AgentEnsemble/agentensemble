package net.agentensemble.transport.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Single shared map of channel -> callback; one entry per active subscription. */
    private final ConcurrentHashMap<String, Consumer<WorkResponse>> subscriptions = new ConcurrentHashMap<>();

    private RedisResultStore(RedisClient client) {
        Objects.requireNonNull(client, "client must not be null");
        this.connection = client.connect();
        this.pubSubConnection = client.connectPubSub();
        this.codec = new RedisCodec();
        installSharedListener();
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

        // Register callback in shared map before subscribing
        subscriptions.put(channel, callback);
        pubSubConnection.sync().subscribe(channel);

        // Check for already-stored result to prevent lost notifications from race conditions.
        WorkResponse existing = retrieve(requestId);
        if (existing != null) {
            Consumer<WorkResponse> removed = subscriptions.remove(channel);
            if (removed != null) {
                pubSubConnection.async().unsubscribe(channel);
                removed.accept(existing);
            }
        }
    }

    @Override
    public void close() {
        subscriptions.clear();
        pubSubConnection.close();
        connection.close();
    }

    // ========================
    // Internal
    // ========================

    /**
     * Installs a single shared Pub/Sub listener that dispatches to per-channel callbacks.
     */
    private void installSharedListener() {
        pubSubConnection.addListener(new RedisPubSubAdapter<String, String>() {
            @Override
            public void message(String channel, String message) {
                Consumer<WorkResponse> callback = subscriptions.remove(channel);
                if (callback != null) {
                    try {
                        WorkResponse response = codec.deserialize(message, WorkResponse.class);
                        pubSubConnection.async().unsubscribe(channel);
                        callback.accept(response);
                    } catch (Exception e) {
                        log.warn(
                                "Failed to deserialize result notification on channel {}: {}",
                                channel,
                                e.getMessage(),
                                e);
                    }
                }
            }
        });
    }

    static String resultKey(String requestId) {
        return KEY_PREFIX + requestId;
    }

    static String notifyChannel(String requestId) {
        return NOTIFY_PREFIX + requestId;
    }
}
