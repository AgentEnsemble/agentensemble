package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;
import net.agentensemble.dashboard.RequestContext;
import net.agentensemble.dashboard.RequestHandler;
import net.agentensemble.web.protocol.WorkResponse;

/**
 * Decorator that adds idempotency and result caching to any {@link RequestHandler}.
 *
 * <p>When an {@link IdempotencyGuard} is configured, duplicate request IDs are detected
 * and the previously computed result is returned without re-execution. When a
 * {@link ResultCache} is configured and the request's cache policy is {@code USE_CACHED},
 * semantically equivalent requests (same cache key) can share cached results.
 *
 * <p>Both guard and cache are optional; pass {@code null} to disable either feature.
 */
public class CachingRequestHandler implements RequestHandler {

    private static final Duration DEFAULT_IDEMPOTENCY_TTL = Duration.ofHours(1);

    private final RequestHandler delegate;
    private final IdempotencyGuard idempotencyGuard;
    private final ResultCache resultCache;

    /**
     * Create a caching decorator around the given handler.
     *
     * @param delegate the handler to delegate to; must not be null
     * @param guard    idempotency guard; may be null (disabled)
     * @param cache    result cache; may be null (disabled)
     */
    public CachingRequestHandler(RequestHandler delegate, IdempotencyGuard guard, ResultCache cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.idempotencyGuard = guard; // nullable (disabled)
        this.resultCache = cache; // nullable (disabled)
    }

    @Override
    public TaskResult handleTaskRequest(String taskName, String context) {
        return delegate.handleTaskRequest(taskName, context);
    }

    @Override
    public ToolResult handleToolRequest(String toolName, String input) {
        return delegate.handleToolRequest(toolName, input);
    }

    @Override
    public TaskResult handleTaskRequest(String taskName, String context, RequestContext ctx) {
        if (ctx == null) {
            return delegate.handleTaskRequest(taskName, context);
        }

        // 1. Idempotency check
        if (idempotencyGuard != null && ctx.requestId() != null) {
            if (!idempotencyGuard.tryAcquire(ctx.requestId())) {
                WorkResponse existing = idempotencyGuard.getExistingResult(ctx.requestId());
                if (existing != null) {
                    return new TaskResult(
                            existing.status(),
                            existing.result(),
                            existing.error(),
                            existing.durationMs() != null ? existing.durationMs() : 0L);
                }
                return new TaskResult("IN_PROGRESS", "Duplicate request (in progress)", null, 0L);
            }
        }

        // 2. Cache check
        if (resultCache != null && "USE_CACHED".equals(ctx.cachePolicy()) && ctx.cacheKey() != null) {
            WorkResponse cached = resultCache.get(ctx.cacheKey());
            if (cached != null) {
                // Release idempotency with cached result
                if (idempotencyGuard != null && ctx.requestId() != null) {
                    idempotencyGuard.release(ctx.requestId(), cached, DEFAULT_IDEMPOTENCY_TTL);
                }
                return new TaskResult(
                        cached.status(),
                        cached.result(),
                        cached.error(),
                        cached.durationMs() != null ? cached.durationMs() : 0L);
            }
        }

        // 3. Execute
        TaskResult result = delegate.handleTaskRequest(taskName, context, ctx);

        // 4. Post-execution: release idempotency + cache
        WorkResponse workResponse = new WorkResponse(
                ctx.requestId() != null ? ctx.requestId() : "unknown",
                result.status(),
                result.result(),
                result.error(),
                result.durationMs());

        if (idempotencyGuard != null && ctx.requestId() != null) {
            idempotencyGuard.release(ctx.requestId(), workResponse, DEFAULT_IDEMPOTENCY_TTL);
        }
        if (resultCache != null && ctx.cacheKey() != null && ctx.maxAge() != null) {
            resultCache.cache(ctx.cacheKey(), workResponse, ctx.maxAge());
        }

        return result;
    }

    @Override
    public ToolResult handleToolRequest(String toolName, String input, RequestContext ctx) {
        // Tools are typically not cached; delegate directly
        return delegate.handleToolRequest(toolName, input, ctx);
    }
}
