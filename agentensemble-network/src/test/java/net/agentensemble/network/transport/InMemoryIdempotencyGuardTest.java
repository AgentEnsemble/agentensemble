package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import net.agentensemble.web.protocol.WorkResponse;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InMemoryIdempotencyGuard}.
 */
class InMemoryIdempotencyGuardTest {

    private final IdempotencyGuard guard = IdempotencyGuard.inMemory();

    @Test
    void tryAcquire_firstTime_returnsTrue() {
        assertThat(guard.tryAcquire("req-1")).isTrue();
    }

    @Test
    void tryAcquire_duplicate_returnsFalse() {
        guard.tryAcquire("req-1");
        assertThat(guard.tryAcquire("req-1")).isFalse();
    }

    @Test
    void getExistingResult_beforeRelease_returnsNull() {
        guard.tryAcquire("req-1");
        // In-progress state -- response is null
        assertThat(guard.getExistingResult("req-1")).isNull();
    }

    @Test
    void getExistingResult_afterRelease_returnsResponse() {
        guard.tryAcquire("req-1");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);
        guard.release("req-1", response, Duration.ofHours(1));

        WorkResponse result = guard.getExistingResult("req-1");
        assertThat(result).isNotNull();
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("done");
    }

    @Test
    void tryAcquire_afterExpiry_allowsReacquisition() throws Exception {
        guard.tryAcquire("req-1");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);
        guard.release("req-1", response, Duration.ofMillis(1));

        Thread.sleep(10);

        // After expiry, tryAcquire should succeed again
        assertThat(guard.tryAcquire("req-1")).isTrue();
    }

    @Test
    void release_storesResponse() {
        guard.tryAcquire("req-1");
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "result", null, 200L);
        guard.release("req-1", response, Duration.ofHours(1));

        // After release, tryAcquire returns false (completed, not expired)
        assertThat(guard.tryAcquire("req-1")).isFalse();
        // And the result is available
        assertThat(guard.getExistingResult("req-1")).isNotNull();
        assertThat(guard.getExistingResult("req-1").result()).isEqualTo("result");
    }

    @Test
    void tryAcquire_nullRequestId_throwsNPE() {
        assertThatThrownBy(() -> guard.tryAcquire(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void release_nullArgs_throwNPE() {
        WorkResponse response = new WorkResponse("req-1", "COMPLETED", "done", null, 100L);

        assertThatThrownBy(() -> guard.release(null, response, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> guard.release("req-1", null, Duration.ofHours(1)))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> guard.release("req-1", response, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void getExistingResult_nullRequestId_throwsNPE() {
        assertThatThrownBy(() -> guard.getExistingResult(null)).isInstanceOf(NullPointerException.class);
    }
}
