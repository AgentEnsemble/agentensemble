package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SubscriptionManager}.
 *
 * <p>Covers: default (no subscription) behaviour, event type filtering, run ID filtering,
 * wildcard reset, and unsubscribe.
 */
class SubscriptionManagerTest {

    private SubscriptionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SubscriptionManager();
    }

    // ========================
    // Default (no subscription) behaviour
    // ========================

    @Test
    void noSubscription_allEventsDelivered() {
        assertThat(manager.shouldDeliver("session-1", "task_started", "{\"type\":\"task_started\"}"))
                .isTrue();
        assertThat(manager.shouldDeliver("session-1", "heartbeat", "{\"type\":\"heartbeat\"}"))
                .isTrue();
        assertThat(manager.shouldDeliver("session-1", "run_result", "{\"type\":\"run_result\"}"))
                .isTrue();
    }

    // ========================
    // Event type filtering
    // ========================

    @Test
    void subscribeToSpecificEvents_onlyMatchingEventsDelivered() {
        manager.subscribe("s1", java.util.List.of("task_started", "task_completed"), null);

        assertThat(manager.shouldDeliver("s1", "task_started", "{\"type\":\"task_started\"}"))
                .isTrue();
        assertThat(manager.shouldDeliver("s1", "task_completed", "{\"type\":\"task_completed\"}"))
                .isTrue();
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isFalse();
        assertThat(manager.shouldDeliver("s1", "heartbeat", "{\"type\":\"heartbeat\"}"))
                .isFalse();
    }

    @Test
    void subscribeToWildcard_allEventsDelivered() {
        // First subscribe to specific events
        manager.subscribe("s1", java.util.List.of("task_started"), null);
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isFalse();

        // Reset with wildcard
        manager.subscribe("s1", java.util.List.of("*"), null);
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isTrue();
    }

    @Test
    void subscribeWithNullOrEmptyEvents_allEventsDelivered() {
        manager.subscribe("s1", java.util.List.of("task_started"), null);
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isFalse();

        // Reset with null events (same as wildcard)
        manager.subscribe("s1", null, null);
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isTrue();
    }

    // ========================
    // Run ID filtering
    // ========================

    @Test
    void subscribeWithRunId_onlyMatchingRunIdEventsDelivered() {
        manager.subscribe("s1", java.util.List.of("run_result"), "run-abc");

        // Matching run ID -- deliver
        assertThat(manager.shouldDeliver("s1", "run_result", "{\"type\":\"run_result\",\"runId\":\"run-abc\"}"))
                .isTrue();

        // Non-matching run ID -- do not deliver
        assertThat(manager.shouldDeliver("s1", "run_result", "{\"type\":\"run_result\",\"runId\":\"run-xyz\"}"))
                .isFalse();
    }

    @Test
    void subscribeWithRunId_systemMessagesWithoutRunIdAlwaysDelivered() {
        manager.subscribe("s1", java.util.List.of("heartbeat", "run_result"), "run-abc");

        // Heartbeat has no runId field -- always deliver
        assertThat(manager.shouldDeliver("s1", "heartbeat", "{\"type\":\"heartbeat\"}"))
                .isTrue();
    }

    @Test
    void subscribeWithRunId_nonMatchingEventTypeFiltered() {
        manager.subscribe("s1", java.util.List.of("run_result"), "run-abc");

        // Event type doesn't match -- do not deliver even if runId matches
        assertThat(manager.shouldDeliver("s1", "task_started", "{\"type\":\"task_started\",\"runId\":\"run-abc\"}"))
                .isFalse();
    }

    // ========================
    // Multiple sessions with different subscriptions
    // ========================

    @Test
    void multipleSessionsWithDifferentSubscriptions_eachFilerIndependently() {
        manager.subscribe("s1", java.util.List.of("task_started"), null);
        manager.subscribe("s2", java.util.List.of("run_result"), null);

        assertThat(manager.shouldDeliver("s1", "task_started", "{\"type\":\"task_started\"}"))
                .isTrue();
        assertThat(manager.shouldDeliver("s1", "run_result", "{\"type\":\"run_result\"}"))
                .isFalse();
        assertThat(manager.shouldDeliver("s2", "task_started", "{\"type\":\"task_started\"}"))
                .isFalse();
        assertThat(manager.shouldDeliver("s2", "run_result", "{\"type\":\"run_result\"}"))
                .isTrue();
    }

    // ========================
    // Unsubscribe
    // ========================

    @Test
    void unsubscribe_revertsToDefaultAllEventsDelivered() {
        manager.subscribe("s1", java.util.List.of("task_started"), null);
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isFalse();

        manager.unsubscribe("s1");
        assertThat(manager.shouldDeliver("s1", "token", "{\"type\":\"token\"}")).isTrue();
    }

    @Test
    void unsubscribeUnknownSession_noException() {
        // Should not throw
        manager.unsubscribe("never-subscribed");
    }

    // ========================
    // getSubscription
    // ========================

    @Test
    void getSubscription_noSubscription_returnsNull() {
        assertThat(manager.getSubscription("unknown")).isNull();
    }

    @Test
    void getSubscription_afterSubscribe_returnsSubscription() {
        manager.subscribe("s1", java.util.List.of("task_started"), "run-abc");

        SubscriptionManager.Subscription sub = manager.getSubscription("s1");
        assertThat(sub).isNotNull();
        assertThat(sub.eventTypes()).contains("task_started");
        assertThat(sub.runId()).isEqualTo("run-abc");
    }
}
