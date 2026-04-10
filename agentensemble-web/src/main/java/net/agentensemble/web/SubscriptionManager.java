package net.agentensemble.web;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session event subscription state for the live dashboard WebSocket.
 *
 * <p>Tracks which event types and (optionally) which run ID each session has subscribed to.
 * When a session sends a {@link net.agentensemble.web.protocol.SubscribeMessage}, its entry
 * is updated here. The {@link ConnectionManager} consults this manager during broadcast to
 * decide whether to deliver each message to each session.
 *
 * <p>Default (no subscription registered): all events are delivered to the session, preserving
 * full backward compatibility with clients that do not send a subscribe message.
 *
 * <h2>Wildcard</h2>
 * <p>A subscription with event type {@code "*"} (wildcard) resets the session to the default
 * (all events delivered). This is equivalent to calling {@link #unsubscribe(String)}.
 *
 * <h2>Run filtering</h2>
 * <p>When a session's subscription includes a non-null {@code runId}, only events whose JSON
 * contains a {@code "runId"} field matching that value are delivered. Events without a
 * {@code "runId"} field (e.g. heartbeat, hello) are always delivered regardless of run filter.
 *
 * <p>Thread safety: uses a {@link ConcurrentHashMap} internally. Individual subscription
 * updates are atomic.
 */
final class SubscriptionManager {

    /** Wildcard event type: deliver all events (resets to default). */
    static final String WILDCARD = "*";

    /**
     * Per-session subscription record.
     *
     * @param eventTypes the set of allowed event type names; empty means all (wildcard registered)
     * @param runId      optional run ID filter; null means no run-level filter
     * @param wildcard   true when event types includes "*" (all events)
     */
    record Subscription(Set<String> eventTypes, String runId, boolean wildcard) {}

    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * Register or update a subscription for the given session.
     *
     * <p>If {@code eventTypes} contains {@code "*"} or is null/empty, the session reverts to
     * the default (all events delivered), equivalent to {@link #unsubscribe(String)}.
     *
     * @param sessionId  the WebSocket session identifier
     * @param eventTypes the event type names to subscribe to; {@code ["*"]} for all
     * @param runId      optional run ID to filter events by; null for no run filter
     */
    void subscribe(String sessionId, List<String> eventTypes, String runId) {
        if (eventTypes == null || eventTypes.isEmpty() || eventTypes.contains(WILDCARD)) {
            subscriptions.remove(sessionId);
            return;
        }
        Set<String> typeSet = ConcurrentHashMap.newKeySet();
        typeSet.addAll(eventTypes);
        subscriptions.put(sessionId, new Subscription(typeSet, runId, false));
    }

    /**
     * Remove any subscription for the given session, reverting it to the default
     * (all events delivered).
     *
     * @param sessionId the WebSocket session identifier
     */
    void unsubscribe(String sessionId) {
        subscriptions.remove(sessionId);
    }

    /**
     * Returns the active subscription for the given session, or {@code null} if the session
     * has no subscription (receives all events by default).
     *
     * @param sessionId the WebSocket session identifier
     * @return the subscription, or null
     */
    Subscription getSubscription(String sessionId) {
        return subscriptions.get(sessionId);
    }

    /**
     * Determines whether the given message should be delivered to the given session.
     *
     * <p>Delivery rules:
     * <ol>
     *   <li>If the session has no subscription (default), always deliver.</li>
     *   <li>If the message type is not in the session's event type whitelist, do not deliver.</li>
     *   <li>If the session has a run ID filter:
     *       <ul>
     *         <li>If the message JSON contains a {@code "runId"} field, deliver only if it matches.</li>
     *         <li>If the message JSON does not contain a {@code "runId"} field, always deliver
     *             (heartbeat, hello, etc. are run-agnostic system messages).</li>
     *       </ul>
     *   </li>
     *   <li>Otherwise, deliver.</li>
     * </ol>
     *
     * @param sessionId   the WebSocket session identifier
     * @param messageType the value of the {@code "type"} field extracted from the JSON
     * @param messageJson the full JSON string being broadcast (used for run ID extraction)
     * @return true if the message should be delivered to this session
     */
    boolean shouldDeliver(String sessionId, String messageType, String messageJson) {
        Subscription sub = subscriptions.get(sessionId);
        if (sub == null) {
            return true; // default: deliver everything
        }

        // Check event type filter
        if (!sub.eventTypes().contains(messageType)) {
            return false;
        }

        // If a run ID filter is set, check the message's runId field (if present)
        String requiredRunId = sub.runId();
        if (requiredRunId != null) {
            String msgRunId = extractRunId(messageJson);
            if (msgRunId != null && !requiredRunId.equals(msgRunId)) {
                return false;
            }
            // msgRunId == null means no runId field -> system message, always deliver
        }

        return true;
    }

    /**
     * Extracts the {@code "runId"} field value from a JSON string without full parsing.
     * Returns {@code null} if the field is absent.
     *
     * <p>Uses a simple string search optimized for the common case of small JSON messages.
     * Only extracts the first occurrence of {@code "runId":"..."}.
     */
    private static String extractRunId(String json) {
        if (json == null) return null;
        int idx = json.indexOf("\"runId\":\"");
        if (idx < 0) return null;
        int start = idx + 9;
        int end = json.indexOf('"', start);
        if (end <= start) return null;
        return json.substring(start, end);
    }
}
