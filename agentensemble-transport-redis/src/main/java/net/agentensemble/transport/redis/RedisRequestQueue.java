package net.agentensemble.transport.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.network.transport.RequestQueue;
import net.agentensemble.web.protocol.WorkRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Streams implementation of {@link RequestQueue}.
 *
 * <p>Uses Redis Streams ({@code XADD}, {@code XREADGROUP}, {@code XACK}, {@code XAUTOCLAIM})
 * for durable, horizontally-scalable work request delivery with consumer group support.
 *
 * <p>Each queue name maps to a Redis Stream keyed {@code agentensemble:queue:{queueName}} with
 * a consumer group named {@code agentensemble:group:{queueName}}. Multiple replicas use
 * different consumer names but share the same group, so Redis distributes messages across
 * consumers.
 *
 * <h2>Visibility timeout</h2>
 *
 * <p>When a consumer dequeues a message, it enters the Pending Entries List (PEL). If the
 * consumer crashes before acknowledging, the message remains pending. On the next
 * {@link #dequeue} call, {@code XAUTOCLAIM} reclaims messages pending longer than the
 * visibility timeout and delivers them to the current consumer.
 *
 * <h2>Usage</h2>
 * <pre>
 * RedisClient client = RedisClient.create("redis://localhost:6379");
 * RedisRequestQueue queue = RedisRequestQueue.create(client);
 *
 * queue.enqueue("kitchen", workRequest);
 * WorkRequest next = queue.dequeue("kitchen", Duration.ofSeconds(30));
 * queue.acknowledge("kitchen", next.requestId());
 * </pre>
 *
 * <p>Thread-safe.
 *
 * @see RequestQueue
 * @see RedisResultStore
 */
public final class RedisRequestQueue implements RequestQueue, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisRequestQueue.class);

    static final String KEY_PREFIX = "agentensemble:queue:";
    static final String GROUP_PREFIX = "agentensemble:group:";
    static final String PAYLOAD_FIELD = "payload";
    static final Duration DEFAULT_VISIBILITY_TIMEOUT = Duration.ofMinutes(5);

    private final StatefulRedisConnection<String, String> connection;
    private final RedisCodec codec;
    private final String consumerName;
    private final Duration visibilityTimeout;

    /** Tracks which queue names have had their consumer group created. */
    private final Set<String> initializedGroups = ConcurrentHashMap.newKeySet();

    /** Maps requestId to Redis stream message ID for {@link #acknowledge}. */
    private final ConcurrentHashMap<String, String> pendingMessageIds = new ConcurrentHashMap<>();

    private RedisRequestQueue(RedisClient client, String consumerName, Duration visibilityTimeout) {
        Objects.requireNonNull(client, "client must not be null");
        this.connection = client.connect();
        this.codec = new RedisCodec();
        this.consumerName = Objects.requireNonNull(consumerName, "consumerName must not be null");
        this.visibilityTimeout = Objects.requireNonNull(visibilityTimeout, "visibilityTimeout must not be null");
    }

    /**
     * Create a Redis request queue with default consumer name and visibility timeout.
     *
     * <p>The consumer name defaults to a random UUID. The visibility timeout defaults to
     * 5 minutes.
     *
     * @param client the Lettuce Redis client; must not be null
     * @return a new {@link RedisRequestQueue}
     */
    public static RedisRequestQueue create(RedisClient client) {
        return new RedisRequestQueue(client, UUID.randomUUID().toString(), DEFAULT_VISIBILITY_TIMEOUT);
    }

    /**
     * Create a Redis request queue with a specific consumer name and default visibility timeout.
     *
     * <p>Use a stable consumer name (e.g., hostname) to enable Redis to track pending
     * messages per consumer across restarts.
     *
     * @param client       the Lettuce Redis client; must not be null
     * @param consumerName unique name for this consumer (e.g., hostname); must not be null
     * @return a new {@link RedisRequestQueue}
     */
    public static RedisRequestQueue create(RedisClient client, String consumerName) {
        return new RedisRequestQueue(client, consumerName, DEFAULT_VISIBILITY_TIMEOUT);
    }

    /**
     * Create a Redis request queue with a specific consumer name and visibility timeout.
     *
     * @param client            the Lettuce Redis client; must not be null
     * @param consumerName      unique name for this consumer; must not be null
     * @param visibilityTimeout how long before unclaimed messages are reclaimed; must not be null
     * @return a new {@link RedisRequestQueue}
     */
    public static RedisRequestQueue create(RedisClient client, String consumerName, Duration visibilityTimeout) {
        return new RedisRequestQueue(client, consumerName, visibilityTimeout);
    }

    @Override
    public void enqueue(String queueName, WorkRequest request) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(request, "request must not be null");

        ensureConsumerGroup(queueName);

        String json = codec.serialize(request);
        RedisCommands<String, String> commands = connection.sync();
        commands.xadd(streamKey(queueName), Map.of(PAYLOAD_FIELD, json));
    }

    @Override
    public WorkRequest dequeue(String queueName, Duration timeout) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        ensureConsumerGroup(queueName);

        RedisCommands<String, String> commands = connection.sync();
        String streamKey = streamKey(queueName);
        String groupName = groupName(queueName);

        // 1. Resume pending entries for this consumer (e.g., after crash/restart with same consumerName).
        //    Offset "0" reads messages already delivered to this consumer but not yet acknowledged.
        WorkRequest pending = readPending(commands, streamKey, groupName);
        if (pending != null) {
            return pending;
        }

        // 2. Try to reclaim timed-out messages from other consumers
        WorkRequest reclaimed = tryAutoClaim(commands, streamKey, groupName);
        if (reclaimed != null) {
            return reclaimed;
        }

        // 3. Read new messages from the stream
        List<StreamMessage<String, String>> messages = commands.xreadgroup(
                io.lettuce.core.Consumer.from(groupName, consumerName),
                XReadArgs.Builder.count(1).block(timeout.toMillis()),
                XReadArgs.StreamOffset.lastConsumed(streamKey));

        if (messages == null || messages.isEmpty()) {
            return null;
        }

        StreamMessage<String, String> message = messages.get(0);
        return parseMessage(message);
    }

    @Override
    public void acknowledge(String queueName, String requestId) {
        Objects.requireNonNull(queueName, "queueName must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");

        String messageId = pendingMessageIds.remove(requestId);
        if (messageId == null) {
            log.debug(
                    "No pending message ID found for requestId={} -- already acknowledged or not dequeued by this consumer",
                    requestId);
            return;
        }

        RedisCommands<String, String> commands = connection.sync();
        String streamKey = streamKey(queueName);
        String groupName = groupName(queueName);

        commands.xack(streamKey, groupName, messageId);
        commands.xdel(streamKey, messageId);
    }

    @Override
    public void close() {
        connection.close();
    }

    /**
     * Returns the consumer name used by this queue instance.
     */
    public String consumerName() {
        return consumerName;
    }

    // ========================
    // Internal
    // ========================

    private WorkRequest readPending(RedisCommands<String, String> commands, String streamKey, String groupName) {
        try {
            // Read up to 10 pending entries and return the first one not already tracked
            // in this session (i.e., from a previous session with the same consumerName).
            List<StreamMessage<String, String>> pending = commands.xreadgroup(
                    io.lettuce.core.Consumer.from(groupName, consumerName),
                    XReadArgs.Builder.count(10),
                    XReadArgs.StreamOffset.from(streamKey, "0"));
            if (pending != null) {
                for (StreamMessage<String, String> msg : pending) {
                    if (!pendingMessageIds.containsValue(msg.getId())) {
                        return parseMessage(msg);
                    }
                }
            }
        } catch (RedisCommandExecutionException e) {
            log.debug("XREADGROUP pending failed for stream={}: {}", streamKey, e.getMessage());
        }
        return null;
    }

    private WorkRequest tryAutoClaim(RedisCommands<String, String> commands, String streamKey, String groupName) {
        try {
            XAutoClaimArgs<String> args = new XAutoClaimArgs<String>()
                    .consumer(io.lettuce.core.Consumer.from(groupName, consumerName))
                    .minIdleTime(visibilityTimeout.toMillis())
                    .startId("0-0")
                    .count(1);
            ClaimedMessages<String, String> claimed = commands.xautoclaim(streamKey, args);

            if (claimed != null
                    && claimed.getMessages() != null
                    && !claimed.getMessages().isEmpty()) {
                return parseMessage(claimed.getMessages().get(0));
            }
        } catch (RedisCommandExecutionException e) {
            // Consumer group might not exist yet or stream is empty
            log.debug("XAUTOCLAIM failed for stream={}: {}", streamKey, e.getMessage());
        }
        return null;
    }

    private WorkRequest parseMessage(StreamMessage<String, String> message) {
        String json = message.getBody().get(PAYLOAD_FIELD);
        WorkRequest request = codec.deserialize(json, WorkRequest.class);
        pendingMessageIds.put(request.requestId(), message.getId());
        return request;
    }

    private void ensureConsumerGroup(String queueName) {
        if (initializedGroups.add(queueName)) {
            try {
                RedisCommands<String, String> commands = connection.sync();
                commands.xgroupCreate(
                        XReadArgs.StreamOffset.from(streamKey(queueName), "0-0"),
                        groupName(queueName),
                        io.lettuce.core.XGroupCreateArgs.Builder.mkstream());
            } catch (RedisCommandExecutionException e) {
                String message = e.getMessage();
                if (message == null || !message.startsWith("BUSYGROUP ")) {
                    initializedGroups.remove(queueName);
                    throw e;
                }
                // BUSYGROUP: consumer group already exists -- safe to ignore
            }
        }
    }

    static String streamKey(String queueName) {
        return KEY_PREFIX + queueName;
    }

    static String groupName(String queueName) {
        return GROUP_PREFIX + queueName;
    }
}
