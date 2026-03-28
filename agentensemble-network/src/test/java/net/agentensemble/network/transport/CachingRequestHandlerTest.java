package net.agentensemble.network.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.agentensemble.dashboard.RequestContext;
import net.agentensemble.dashboard.RequestHandler;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachingRequestHandler}.
 */
class CachingRequestHandlerTest {

    // ========================
    // No caching configured
    // ========================

    @Test
    void handleTask_noCachingConfigured_delegatesToHandler() {
        CountingHandler delegate = new CountingHandler("result-1");
        CachingRequestHandler handler = new CachingRequestHandler(delegate, null, null);

        RequestContext ctx = new RequestContext("req-1", null, null, null);
        RequestHandler.TaskResult result = handler.handleTaskRequest("task", "context", ctx);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("result-1");
        assertThat(delegate.taskCallCount).isEqualTo(1);
    }

    // ========================
    // Idempotency
    // ========================

    @Test
    void handleTask_duplicateRequestId_returnsCached() {
        CountingHandler delegate = new CountingHandler("result-1");
        IdempotencyGuard guard = IdempotencyGuard.inMemory();
        CachingRequestHandler handler = new CachingRequestHandler(delegate, guard, null);

        RequestContext ctx = new RequestContext("req-1", null, null, null);

        // First call executes
        RequestHandler.TaskResult first = handler.handleTaskRequest("task", "context", ctx);
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.result()).isEqualTo("result-1");
        assertThat(delegate.taskCallCount).isEqualTo(1);

        // Second call with same requestId returns cached result
        RequestHandler.TaskResult second = handler.handleTaskRequest("task", "context", ctx);
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(second.result()).isEqualTo("result-1");
        // Delegate was NOT called a second time
        assertThat(delegate.taskCallCount).isEqualTo(1);
    }

    // ========================
    // Cache hit
    // ========================

    @Test
    void handleTask_useCached_cacheHit_returnsWithoutExecution() {
        CountingHandler delegate = new CountingHandler("fresh-result");
        ResultCache cache = ResultCache.inMemory();
        CachingRequestHandler handler = new CachingRequestHandler(delegate, null, cache);

        // Pre-populate cache
        var cachedResponse =
                new net.agentensemble.web.protocol.WorkResponse("req-0", "COMPLETED", "cached-result", null, 50L);
        cache.cache("my-cache-key", cachedResponse, Duration.ofHours(1));

        RequestContext ctx = new RequestContext("req-1", "my-cache-key", "USE_CACHED", Duration.ofHours(1));
        RequestHandler.TaskResult result = handler.handleTaskRequest("task", "context", ctx);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("cached-result");
        // Delegate was NOT called
        assertThat(delegate.taskCallCount).isEqualTo(0);
    }

    // ========================
    // Cache miss
    // ========================

    @Test
    void handleTask_useCached_cacheMiss_executesAndCaches() {
        CountingHandler delegate = new CountingHandler("fresh-result");
        ResultCache cache = ResultCache.inMemory();
        CachingRequestHandler handler = new CachingRequestHandler(delegate, null, cache);

        RequestContext ctx = new RequestContext("req-1", "my-cache-key", "USE_CACHED", Duration.ofHours(1));
        RequestHandler.TaskResult result = handler.handleTaskRequest("task", "context", ctx);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("fresh-result");
        assertThat(delegate.taskCallCount).isEqualTo(1);

        // Verify the result was cached
        var cached = cache.get("my-cache-key");
        assertThat(cached).isNotNull();
        assertThat(cached.result()).isEqualTo("fresh-result");
    }

    // ========================
    // Force fresh
    // ========================

    @Test
    void handleTask_forceFresh_bypasesCache() {
        CountingHandler delegate = new CountingHandler("fresh-result");
        ResultCache cache = ResultCache.inMemory();
        CachingRequestHandler handler = new CachingRequestHandler(delegate, null, cache);

        // Pre-populate cache
        var cachedResponse =
                new net.agentensemble.web.protocol.WorkResponse("req-0", "COMPLETED", "cached-result", null, 50L);
        cache.cache("my-cache-key", cachedResponse, Duration.ofHours(1));

        // Use FORCE_FRESH policy
        RequestContext ctx = new RequestContext("req-1", "my-cache-key", "FORCE_FRESH", Duration.ofHours(1));
        RequestHandler.TaskResult result = handler.handleTaskRequest("task", "context", ctx);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("fresh-result");
        assertThat(delegate.taskCallCount).isEqualTo(1);
    }

    // ========================
    // Null context
    // ========================

    @Test
    void handleTask_nullContext_delegatesToBasicHandler() {
        CountingHandler delegate = new CountingHandler("result-1");
        IdempotencyGuard guard = IdempotencyGuard.inMemory();
        ResultCache cache = ResultCache.inMemory();
        CachingRequestHandler handler = new CachingRequestHandler(delegate, guard, cache);

        RequestHandler.TaskResult result = handler.handleTaskRequest("task", "context", null);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("result-1");
        assertThat(delegate.taskCallCount).isEqualTo(1);
    }

    // ========================
    // Tools
    // ========================

    @Test
    void handleTool_delegatesToHandler() {
        CountingHandler delegate = new CountingHandler("result-1");
        CachingRequestHandler handler = new CachingRequestHandler(delegate, null, null);

        RequestHandler.ToolResult result = handler.handleToolRequest("tool", "input");
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("tool-result");
        assertThat(delegate.toolCallCount).isEqualTo(1);
    }

    // ========================
    // Test double
    // ========================

    private static class CountingHandler implements RequestHandler {

        private final String taskResult;
        int taskCallCount = 0;
        int toolCallCount = 0;

        CountingHandler(String taskResult) {
            this.taskResult = taskResult;
        }

        @Override
        public TaskResult handleTaskRequest(String taskName, String context) {
            taskCallCount++;
            return new TaskResult("COMPLETED", taskResult, null, 100L);
        }

        @Override
        public ToolResult handleToolRequest(String toolName, String input) {
            toolCallCount++;
            return new ToolResult("COMPLETED", "tool-result", null, 50L);
        }
    }
}
