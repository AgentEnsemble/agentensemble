package net.agentensemble.directive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectiveStoreTest {

    private DirectiveStore store;

    @BeforeEach
    void setUp() {
        store = new DirectiveStore();
    }

    // ========================
    // add / remove
    // ========================

    @Test
    void add_storesDirective() {
        Directive d = contextDirective("d1", "user", "Focus on quality");
        store.add(d);

        assertThat(store.all()).hasSize(1);
        assertThat(store.all().getFirst().id()).isEqualTo("d1");
    }

    @Test
    void add_nullThrows() {
        assertThatThrownBy(() -> store.add(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be null");
    }

    @Test
    void remove_removesById() {
        store.add(contextDirective("d1", "user", "Directive 1"));
        store.add(contextDirective("d2", "user", "Directive 2"));

        store.remove("d1");

        assertThat(store.all()).hasSize(1);
        assertThat(store.all().getFirst().id()).isEqualTo("d2");
    }

    @Test
    void remove_nonExistentId_noOp() {
        store.add(contextDirective("d1", "user", "content"));
        store.remove("nonexistent");

        assertThat(store.all()).hasSize(1);
    }

    // ========================
    // activeContextDirectives
    // ========================

    @Test
    void activeContextDirectives_filtersExpired() {
        store.add(contextDirective("d1", "user", "Active"));
        store.add(expiredContextDirective("d2", "user", "Expired"));

        List<Directive> active = store.activeContextDirectives();

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().id()).isEqualTo("d1");
    }

    @Test
    void activeContextDirectives_filtersControlPlane() {
        store.add(contextDirective("d1", "user", "Context directive"));
        store.add(controlPlaneDirective("d2", "user", "SET_MODEL_TIER", "fallback"));

        List<Directive> active = store.activeContextDirectives();

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().id()).isEqualTo("d1");
        assertThat(active.getFirst().isContextDirective()).isTrue();
    }

    // ========================
    // activeControlPlaneDirectives
    // ========================

    @Test
    void activeControlPlaneDirectives_filtersExpired() {
        store.add(controlPlaneDirective("d1", "admin", "SET_MODEL_TIER", "fallback"));
        store.add(new Directive(
                "d2",
                "admin",
                null,
                "APPLY_PROFILE",
                "high-quality",
                Instant.now(),
                Instant.now().minusSeconds(60)));

        List<Directive> active = store.activeControlPlaneDirectives();

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().id()).isEqualTo("d1");
    }

    @Test
    void activeControlPlaneDirectives_filtersContext() {
        store.add(contextDirective("d1", "user", "Context directive"));
        store.add(controlPlaneDirective("d2", "admin", "SET_MODEL_TIER", "fallback"));

        List<Directive> active = store.activeControlPlaneDirectives();

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().id()).isEqualTo("d2");
        assertThat(active.getFirst().isControlPlaneDirective()).isTrue();
    }

    // ========================
    // expiry
    // ========================

    @Test
    void expireStale_removesExpiredDirectives() {
        store.add(contextDirective("d1", "user", "Active"));
        store.add(expiredContextDirective("d2", "user", "Expired"));

        store.expireStale();

        assertThat(store.all()).hasSize(1);
        assertThat(store.all().getFirst().id()).isEqualTo("d1");
    }

    @Test
    void directive_withNoExpiry_neverExpires() {
        Directive d = new Directive("d1", "user", "No expiry", null, null, Instant.now(), null);
        assertThat(d.isExpired()).isFalse();
    }

    @Test
    void directive_withFutureExpiry_notExpired() {
        Directive d = new Directive(
                "d1", "user", "Future", null, null, Instant.now(), Instant.now().plusSeconds(3600));
        assertThat(d.isExpired()).isFalse();
    }

    @Test
    void directive_withPastExpiry_isExpired() {
        Directive d = new Directive(
                "d1", "user", "Past", null, null, Instant.now(), Instant.now().minusSeconds(60));
        assertThat(d.isExpired()).isTrue();
    }

    // ========================
    // all()
    // ========================

    @Test
    void all_returnsImmutableCopy() {
        store.add(contextDirective("d1", "user", "content"));

        List<Directive> all = store.all();

        assertThat(all).hasSize(1);
        assertThatThrownBy(() -> all.add(contextDirective("d2", "user", "another")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // thread safety
    // ========================

    @Test
    void concurrentAddAndRead_doesNotThrow() throws Exception {
        int writerCount = 10;
        int readerCount = 10;
        int opsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(writerCount + readerCount);
        CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(writerCount + readerCount);
        try {
            // Writers
            for (int w = 0; w < writerCount; w++) {
                int writerId = w;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            store.add(contextDirective("w" + writerId + "-" + i, "writer-" + writerId, "content-" + i));
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Readers
            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            store.activeContextDirectives();
                            store.all();
                        }
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(errors).isEmpty();
            assertThat(store.all()).hasSize(writerCount * opsPerThread);
        } finally {
            executor.shutdownNow();
        }
    }

    // ========================
    // Directive record helpers
    // ========================

    @Test
    void directive_isContextDirective() {
        Directive d = new Directive("d1", "user", "content", null, null, Instant.now(), null);
        assertThat(d.isContextDirective()).isTrue();
        assertThat(d.isControlPlaneDirective()).isFalse();
    }

    @Test
    void directive_isControlPlaneDirective() {
        Directive d = new Directive("d1", "admin", null, "SET_MODEL_TIER", "fallback", Instant.now(), null);
        assertThat(d.isContextDirective()).isFalse();
        assertThat(d.isControlPlaneDirective()).isTrue();
    }

    // ========================
    // Test helpers
    // ========================

    private static Directive contextDirective(String id, String from, String content) {
        return new Directive(id, from, content, null, null, Instant.now(), null);
    }

    private static Directive expiredContextDirective(String id, String from, String content) {
        return new Directive(
                id, from, content, null, null, Instant.now(), Instant.now().minusSeconds(60));
    }

    private static Directive controlPlaneDirective(String id, String from, String action, String value) {
        return new Directive(id, from, null, action, value, Instant.now(), null);
    }
}
