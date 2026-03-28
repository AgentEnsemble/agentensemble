package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryResultCache}.
 */
class InMemoryResultCacheTest {

    private final ResultCache cache = ResultCache.inMemory();

    @Test
    void cache_and_get_returnsResponse() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, 100L);
        cache.cache("key-1", response, Duration.ofHours(1));

        WorkResponse retrieved = cache.get("key-1");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.requestId()).isEqualTo("req-1");
        assertThat(retrieved.status()).isEqualTo("COMPLETED");
        assertThat(retrieved.result()).isEqualTo("result");
    }

    @Test
    void get_unknownKey_returnsNull() {
        assertThat(cache.get("nonexistent")).isNull();
    }

    @Test
    void get_expiredEntry_returnsNull() throws Exception {
        WorkResponse response = new WorkResponse("req-2", "COMPLETED", "result", null, 50L);
        cache.cache("key-2", response, Duration.ofMillis(1));

        Thread.sleep(10);

        assertThat(cache.get("key-2")).isNull();
    }

    @Test
    void cache_overwrites_existingKey() {
        WorkResponse first = new WorkResponse("req-1", "COMPLETED", "first", null, 100L);
        WorkResponse second = new WorkResponse("req-2", "COMPLETED", "second", null, 200L);

        cache.cache("key", first, Duration.ofHours(1));
        cache.cache("key", second, Duration.ofHours(1));

        WorkResponse retrieved = cache.get("key");
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.result()).isEqualTo("second");
    }

    @Test
    void cache_nullKey_throwsNPE() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, 100L);
        assertThatThrownBy(() -> cache.cache(null, response, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cache_nullResponse_throwsNPE() {
        assertThatThrownBy(() -> cache.cache("key", null, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cache_nullMaxAge_throwsNPE() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, 100L);
        assertThatThrownBy(() -> cache.cache("key", response, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void get_nullKey_throwsNPE() {
        assertThatThrownBy(() -> cache.get(null)).isInstanceOf(NullPointerException.class);
    }
}
