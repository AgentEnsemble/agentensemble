package net.agentensemble.transport.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisCommandExecutionException;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RedisRequestQueue} helpers that do not require Redis.
 */
class RedisRequestQueueUnitTest {

    @Test
    void isExpectedStreamError_nogroup_true() {
        var e = new RedisCommandExecutionException("NOGROUP No such consumer group");
        assertThat(RedisRequestQueue.isExpectedStreamError(e)).isTrue();
    }

    @Test
    void isExpectedStreamError_noSuchKey_true() {
        var e = new RedisCommandExecutionException("ERR no such key");
        assertThat(RedisRequestQueue.isExpectedStreamError(e)).isTrue();
    }

    @Test
    void isExpectedStreamError_authFailure_false() {
        var e = new RedisCommandExecutionException("NOAUTH Authentication required");
        assertThat(RedisRequestQueue.isExpectedStreamError(e)).isFalse();
    }

    @Test
    void isExpectedStreamError_nullMessage_false() {
        var e = new RedisCommandExecutionException((String) null);
        assertThat(RedisRequestQueue.isExpectedStreamError(e)).isFalse();
    }

    @Test
    void isExpectedStreamError_genericError_false() {
        var e = new RedisCommandExecutionException("ERR some other error");
        assertThat(RedisRequestQueue.isExpectedStreamError(e)).isFalse();
    }

    @Test
    void streamKey_includesPrefix() {
        assertThat(RedisRequestQueue.streamKey("kitchen")).isEqualTo("agentensemble:queue:kitchen");
    }

    @Test
    void groupName_includesPrefix() {
        assertThat(RedisRequestQueue.groupName("kitchen")).isEqualTo("agentensemble:group:kitchen");
    }
}
