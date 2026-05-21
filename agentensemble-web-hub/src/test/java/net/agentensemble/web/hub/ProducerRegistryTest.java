package net.agentensemble.web.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProducerRegistry}: identity + reconnect semantics, capacity-based
 * eviction, and idle-eviction callbacks.
 */
class ProducerRegistryTest {

    private final MessageSerializer serializer = new MessageSerializer();

    @Test
    void getOrCreateReturnsSameStateForSameProducerId() {
        ProducerRegistry registry = new ProducerRegistry(serializer, 10, 5, 5);

        ProducerState first = registry.getOrCreate(ProducerInfo.of("p1", "svc"));
        ProducerState second = registry.getOrCreate(ProducerInfo.of("p1", "svc"));

        assertThat(second).isSameAs(first);
    }

    @Test
    void reconnectingWithSameIdPreservesSnapshot() {
        ProducerRegistry registry = new ProducerRegistry(serializer, 10, 5, 5);
        ProducerState first = registry.getOrCreate(ProducerInfo.of("p1", "svc"));
        first.snapshot().noteEnsembleStarted("run-1", java.time.Instant.now());
        first.snapshot().appendToSnapshot("{\"type\":\"task_started\"}");

        registry.markInactive("p1");
        ProducerState rejoin = registry.getOrCreate(ProducerInfo.of("p1", "svc"));

        assertThat(rejoin).isSameAs(first);
        assertThat(rejoin.snapshot().flattenedSnapshotMessages()).hasSize(1);
    }

    @Test
    void differentProducerIdYieldsDistinctState() {
        ProducerRegistry registry = new ProducerRegistry(serializer, 10, 5, 5);
        ProducerState a = registry.getOrCreate(ProducerInfo.of("p1", "svc"));
        ProducerState b = registry.getOrCreate(ProducerInfo.of("p2", "svc"));
        assertThat(b).isNotSameAs(a);
    }

    @Test
    void capacityEvictionRemovesOldestInactive() {
        ProducerRegistry registry = new ProducerRegistry(serializer, 2, 5, 5);
        registry.getOrCreate(ProducerInfo.of("p1", "svc"));
        registry.getOrCreate(ProducerInfo.of("p2", "svc"));
        registry.markInactive("p1");
        // Triggers eviction: should drop p1 (the only inactive).
        registry.getOrCreate(ProducerInfo.of("p3", "svc"));

        List<String> ids = new ArrayList<>();
        registry.listProducers().forEach(p -> ids.add(p.producerId()));
        assertThat(ids).contains("p2", "p3");
        assertThat(ids).doesNotContain("p1");
    }

    @Test
    void evictIdleInvokesCallbackForExpiredInactiveProducers() throws InterruptedException {
        ProducerRegistry registry = new ProducerRegistry(serializer, 10, 5, 5);
        registry.getOrCreate(ProducerInfo.of("p1", "svc"));
        registry.markInactive("p1");

        // Wait past the idle threshold.
        Thread.sleep(50);

        List<String> evicted = new ArrayList<>();
        registry.evictIdle(Duration.ofMillis(1), (id, reason) -> evicted.add(id));

        assertThat(evicted).containsExactly("p1");
        assertThat(registry.listProducers()).isEmpty();
    }

    @Test
    void invalidConstructorArgumentsAreRejected() {
        assertThatThrownBy(() -> new ProducerRegistry(serializer, 0, 5, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProducerRegistry(serializer, 10, 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProducerRegistry(serializer, 10, 5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
