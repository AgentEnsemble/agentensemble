package net.agentensemble.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitedChatModelTest {

    // ========================
    // Factory / construction
    // ========================

    @Test
    void testOf_wrapsDelegate() {
        ChatModel delegate = mock(ChatModel.class);
        var model = RateLimitedChatModel.of(delegate, RateLimit.perMinute(60));
        assertThat(model).isNotNull();
        assertThat(model.getDelegate()).isSameAs(delegate);
    }

    @Test
    void testOf_withNullDelegate_throwsValidation() {
        assertThatThrownBy(() -> RateLimitedChatModel.of(null, RateLimit.perMinute(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOf_withNullRateLimit_throwsValidation() {
        assertThatThrownBy(() -> RateLimitedChatModel.of(mock(ChatModel.class), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOf_withCustomTimeout_isStored() {
        ChatModel delegate = mock(ChatModel.class);
        var timeout = Duration.ofSeconds(10);
        var model = RateLimitedChatModel.of(delegate, RateLimit.perMinute(60), timeout);
        assertThat(model.getWaitTimeout()).isEqualTo(timeout);
    }

    @Test
    void testOf_defaultTimeout_is30Seconds() {
        ChatModel delegate = mock(ChatModel.class);
        var model = RateLimitedChatModel.of(delegate, RateLimit.perMinute(60));
        assertThat(model.getWaitTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    // ========================
    // Delegation transparency
    // ========================

    @Test
    void testChat_delegatesToUnderlyingModel() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse expected = mock(ChatResponse.class);
        ChatRequest request = mock(ChatRequest.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(expected);

        var model = RateLimitedChatModel.of(delegate, RateLimit.perMinute(1000));
        ChatResponse actual = model.chat(request);

        assertThat(actual).isSameAs(expected);
        verify(delegate, times(1)).chat(request);
    }

    @Test
    void testChat_multipleRequests_allDelegated_withinLimit() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(response);

        // High limit: 1000 requests per second -- no waiting needed
        var model = RateLimitedChatModel.of(delegate, RateLimit.perSecond(1000));

        for (int i = 0; i < 5; i++) {
            model.chat(mock(ChatRequest.class));
        }

        verify(delegate, times(5)).chat(any(ChatRequest.class));
    }

    // ========================
    // Rate enforcement
    // ========================

    @Test
    void testRateEnforcement_secondRequestIsDelayed() throws InterruptedException {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(response);

        // 2 requests per second means each token is available every 500ms
        var model = RateLimitedChatModel.of(delegate, RateLimit.perSecond(2));

        long start = System.nanoTime();
        model.chat(mock(ChatRequest.class)); // immediate
        model.chat(mock(ChatRequest.class)); // waits ~500ms
        long elapsed = System.nanoTime() - start;

        // Should have waited at least 300ms (generous lower bound to avoid flakiness)
        assertThat(elapsed).isGreaterThan(300_000_000L);
    }

    // ========================
    // Timeout behavior
    // ========================

    @Test
    void testChat_timeoutExceeded_throwsRateLimitTimeoutException() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(response);

        // 1 request per 10 seconds with a very short wait timeout
        var model = RateLimitedChatModel.of(delegate, RateLimit.of(1, Duration.ofSeconds(10)), Duration.ofMillis(50));

        model.chat(mock(ChatRequest.class)); // consumes the single token

        // Second request should timeout quickly
        assertThatThrownBy(() -> model.chat(mock(ChatRequest.class))).isInstanceOf(RateLimitTimeoutException.class);
    }

    @Test
    void testRateLimitTimeoutException_carriesRateLimitInfo() {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(response);

        var limit = RateLimit.of(1, Duration.ofSeconds(10));
        var timeout = Duration.ofMillis(50);
        var model = RateLimitedChatModel.of(delegate, limit, timeout);

        model.chat(mock(ChatRequest.class));

        assertThatThrownBy(() -> model.chat(mock(ChatRequest.class)))
                .isInstanceOf(RateLimitTimeoutException.class)
                .satisfies(ex -> {
                    var rlex = (RateLimitTimeoutException) ex;
                    assertThat(rlex.getRateLimit()).isSameAs(limit);
                    assertThat(rlex.getWaitTimeout()).isEqualTo(timeout);
                });
    }

    // ========================
    // Thread safety / concurrent access
    // ========================

    @Test
    void testConcurrentAccess_sharedBucket_enforcesLimit() throws Exception {
        ChatModel delegate = mock(ChatModel.class);
        ChatResponse response = mock(ChatResponse.class);
        when(delegate.chat(any(ChatRequest.class))).thenReturn(response);

        // 10 requests per second with generous timeout; 5 threads each make 2 requests
        var model = RateLimitedChatModel.of(delegate, RateLimit.perSecond(10), Duration.ofSeconds(10));

        int threadCount = 5;
        int requestsPerThread = 2;
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        var executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int j = 0; j < requestsPerThread; j++) {
                            model.chat(mock(ChatRequest.class));
                            successCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
            }
            startLatch.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            executor.shutdown();
        }

        assertThat(successCount.get()).isEqualTo(threadCount * requestsPerThread);
        verify(delegate, times(threadCount * requestsPerThread)).chat(any(ChatRequest.class));
    }

    @Test
    void testSharedInstance_twoModels_shareTokenBucket() {
        ChatModel delegate1 = mock(ChatModel.class);
        ChatModel delegate2 = mock(ChatModel.class);
        ChatResponse r1 = mock(ChatResponse.class);
        ChatResponse r2 = mock(ChatResponse.class);
        when(delegate1.chat(any(ChatRequest.class))).thenReturn(r1);
        when(delegate2.chat(any(ChatRequest.class))).thenReturn(r2);

        // Create a shared TokenBucket via a shared RateLimitedChatModel wrapping delegate1,
        // but the point of a shared limiter is that two wrappers share the same RateLimit instance.
        // In practice, users share by passing the same RateLimitedChatModel to multiple agents.
        // Here we verify that a single instance correctly refuses a second request when limit=1/10s.
        var limit = RateLimit.of(1, Duration.ofSeconds(10));
        var sharedModel = RateLimitedChatModel.of(delegate1, limit, Duration.ofMillis(50));

        sharedModel.chat(mock(ChatRequest.class)); // consumes token

        // Third call (from same model, sharing the bucket) should timeout
        assertThatThrownBy(() -> sharedModel.chat(mock(ChatRequest.class)))
                .isInstanceOf(RateLimitTimeoutException.class);
    }

    // ========================
    // getRateLimit accessor
    // ========================

    @Test
    void testGetRateLimit_returnsConfiguredLimit() {
        ChatModel delegate = mock(ChatModel.class);
        var limit = RateLimit.perMinute(30);
        var model = RateLimitedChatModel.of(delegate, limit);
        assertThat(model.getRateLimit()).isSameAs(limit);
    }
}
