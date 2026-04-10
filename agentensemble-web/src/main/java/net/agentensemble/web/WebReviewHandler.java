package net.agentensemble.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTimeoutException;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import net.agentensemble.web.protocol.ReviewRequestedMessage;
import net.agentensemble.web.protocol.ReviewTimedOutMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ReviewHandler} implementation that broadcasts review gate requests over WebSocket
 * and blocks the calling JVM thread until a connected browser client returns a decision
 * (or the review timeout expires).
 *
 * <p>When a review gate fires, this handler:
 * <ol>
 *   <li>Generates a unique {@code reviewId} for this gate invocation.</li>
 *   <li>Registers a {@link CompletableFuture} in the {@link ConnectionManager} under that ID.</li>
 *   <li>Broadcasts a {@code review_requested} protocol message to all connected clients.</li>
 *   <li>Blocks on {@link CompletableFuture#get(long, TimeUnit)} for the configured timeout.</li>
 *   <li>Maps the browser's {@code review_decision} message to a {@link ReviewDecision}.</li>
 * </ol>
 *
 * <p>Timeout and disconnect handling:
 * <ul>
 *   <li>If the timeout expires ({@link TimeoutException}), the configured
 *       {@link OnTimeoutAction} is applied: {@code CONTINUE}, {@code EXIT_EARLY}, or
 *       {@code FAIL} (throws {@link ReviewTimeoutException}).</li>
 *   <li>If all clients disconnect while the review is pending, the
 *       {@link ConnectionManager} resolves the future with an empty string, which this
 *       handler treats the same as a timeout.</li>
 * </ul>
 *
 * <p>Browser decision values (case-insensitive):
 * <ul>
 *   <li>{@code "approve"} or {@code "continue"} -- {@link ReviewDecision#continueExecution()}</li>
 *   <li>{@code "edit"} -- {@link ReviewDecision#edit(String)} using {@code revisedOutput}</li>
 *   <li>Any other value -- {@link ReviewDecision#exitEarly()}</li>
 * </ul>
 *
 * <p>An instance is created by {@link WebDashboard} at build time and exposed via
 * {@link WebDashboard#reviewHandler()}. Client-message routing (connecting the WebSocket
 * server's incoming message stream to this handler) is set up in {@link WebDashboard#start()}.
 *
 * <p>Thread safety: may be called concurrently from multiple virtual threads in a parallel
 * workflow. Each call uses a separate UUID-keyed future, so concurrent reviews are
 * independently tracked.
 */
public final class WebReviewHandler implements ReviewHandler {

    private static final Logger log = LoggerFactory.getLogger(WebReviewHandler.class);

    private final ConnectionManager connectionManager;
    private final MessageSerializer serializer;
    private final Duration reviewTimeout;
    private final OnTimeoutAction onTimeout;

    /**
     * Package-private constructor; instantiated exclusively by {@link WebDashboard}.
     *
     * @param connectionManager the session registry and pending-review map
     * @param serializer        the JSON serializer for protocol messages
     * @param reviewTimeout     how long to wait for a browser decision; must not be null or negative
     * @param onTimeout         what to do when the timeout expires; must not be null
     */
    WebReviewHandler(
            ConnectionManager connectionManager,
            MessageSerializer serializer,
            Duration reviewTimeout,
            OnTimeoutAction onTimeout) {
        this.connectionManager = connectionManager;
        this.serializer = serializer;
        this.reviewTimeout = reviewTimeout;
        this.onTimeout = onTimeout;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Broadcasts the review gate to connected browser clients and blocks until:
     * <ul>
     *   <li>A browser returns a {@code review_decision} message.</li>
     *   <li>The configured {@link #reviewTimeout} expires.</li>
     *   <li>All connected clients disconnect while the review is pending.</li>
     * </ul>
     */
    @Override
    public ReviewDecision review(ReviewRequest request) {
        String reviewId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();

        // Build review metadata for REST-based discovery (GET /api/reviews)
        Duration effectiveTimeout = request.timeout() != null ? request.timeout() : reviewTimeout;
        Instant createdAt = Instant.now();
        Instant expiresAt =
                (effectiveTimeout != null && !effectiveTimeout.isZero()) ? createdAt.plus(effectiveTimeout) : null;
        ConnectionManager.PendingReviewInfo info = new ConnectionManager.PendingReviewInfo(
                reviewId,
                null, // runId unknown at this level; set by RunManager for API runs
                request.taskDescription(),
                request.taskOutput() != null ? request.taskOutput() : "",
                request.timing() != null ? request.timing().name() : "AFTER_EXECUTION",
                request.prompt(),
                effectiveTimeout != null ? effectiveTimeout.toMillis() : 0L,
                createdAt,
                expiresAt);

        connectionManager.registerPendingReview(reviewId, future, info);
        broadcastReviewRequested(reviewId, request);

        if (log.isDebugEnabled()) {
            log.debug("Review gate {} opened for task: {}", reviewId, truncate(request.taskDescription(), 80));
        }

        try {
            // effectiveTimeout is already computed above for the metadata; reuse it here.
            // Duration.ZERO means wait indefinitely (no timeout).
            String rawDecision;
            if (effectiveTimeout.isZero()) {
                // No timeout -- wait indefinitely for a qualified human to respond
                rawDecision = future.get();
            } else {
                rawDecision = future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }

            // Empty string means all clients disconnected; treat as timeout
            if (rawDecision == null || rawDecision.isEmpty()) {
                log.debug("Review {} resolved with empty value (client disconnect); applying timeout action", reviewId);
                return applyTimeoutAction(reviewId, request);
            }

            ReviewDecisionMessage decision = serializer.fromJson(rawDecision, ReviewDecisionMessage.class);
            if (log.isDebugEnabled()) {
                log.debug("Review {} received decision: {}", reviewId, decision.decision());
            }
            return mapToReviewDecision(decision);

        } catch (TimeoutException e) {
            log.info("Review gate {} timed out after {}", reviewId, reviewTimeout);
            // Remove from map so a late decision is silently discarded
            connectionManager.resolveReview(reviewId, "");
            return applyTimeoutAction(reviewId, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Review gate {} interrupted; exiting early", reviewId);
            connectionManager.resolveReview(reviewId, "");
            return ReviewDecision.exitEarly();
        } catch (ExecutionException e) {
            log.warn("Review gate {} future completed exceptionally; applying timeout action", reviewId, e);
            // Remove the map entry to prevent a leak; resolveReview is idempotent if the
            // entry was already removed by an earlier onDisconnect/resolveReview call.
            connectionManager.resolveReview(reviewId, "");
            return applyTimeoutAction(reviewId, request);
        }
    }

    // ========================
    // Private helpers
    // ========================

    private void broadcastReviewRequested(String reviewId, ReviewRequest request) {
        try {
            Duration effectiveTimeout = request.timeout() != null ? request.timeout() : reviewTimeout;
            OnTimeoutAction effectiveOnTimeout =
                    request.onTimeoutAction() != null ? request.onTimeoutAction() : onTimeout;

            ReviewRequestedMessage msg = new ReviewRequestedMessage(
                    reviewId,
                    request.taskDescription(),
                    request.taskOutput() != null ? request.taskOutput() : "",
                    request.timing() != null ? request.timing().name() : "AFTER_EXECUTION",
                    request.prompt(),
                    effectiveTimeout.toMillis(),
                    effectiveOnTimeout.name(),
                    request.requiredRole());
            connectionManager.broadcast(serializer.toJson(msg));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to broadcast review_requested for review {}: {}", reviewId, e.getMessage(), e);
            }
        }
    }

    /**
     * Maps a {@link ReviewDecisionMessage} received from the browser to a {@link ReviewDecision}.
     *
     * <p>Decision values are matched case-insensitively:
     * <ul>
     *   <li>{@code approve} / {@code continue} -> {@link ReviewDecision#continueExecution()}</li>
     *   <li>{@code edit} -> {@link ReviewDecision#edit(String)} with {@code revisedOutput}</li>
     *   <li>anything else -> {@link ReviewDecision#exitEarly()}</li>
     * </ul>
     */
    private ReviewDecision mapToReviewDecision(ReviewDecisionMessage decision) {
        String d = decision.decision() != null
                ? decision.decision().toLowerCase(Locale.ROOT).strip()
                : "";
        return switch (d) {
            case "approve", "continue" -> ReviewDecision.continueExecution();
            case "edit" -> {
                String revised = decision.revisedOutput() != null ? decision.revisedOutput() : "";
                yield ReviewDecision.edit(revised);
            }
            default -> ReviewDecision.exitEarly();
        };
    }

    /**
     * Broadcasts a {@code review_timed_out} message and applies the configured
     * {@link OnTimeoutAction}:
     * <ul>
     *   <li>{@code CONTINUE} -- returns {@link ReviewDecision#continueExecution()}</li>
     *   <li>{@code EXIT_EARLY} -- returns {@link ReviewDecision#exitEarlyTimeout()}</li>
     *   <li>{@code FAIL} -- throws {@link ReviewTimeoutException}</li>
     * </ul>
     */
    private ReviewDecision applyTimeoutAction(String reviewId, ReviewRequest request) {
        broadcastTimedOut(reviewId);
        return switch (onTimeout) {
            case CONTINUE -> ReviewDecision.continueExecution();
            case EXIT_EARLY -> ReviewDecision.exitEarlyTimeout();
            case FAIL -> throw new ReviewTimeoutException("Review gate timed out after " + reviewTimeout
                    + " waiting for browser decision on task: "
                    + truncate(request.taskDescription(), 120));
        };
    }

    private void broadcastTimedOut(String reviewId) {
        try {
            ReviewTimedOutMessage msg = new ReviewTimedOutMessage(reviewId, onTimeout.name());
            connectionManager.broadcast(serializer.toJson(msg));
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Failed to broadcast review_timed_out for review {}: {}", reviewId, e.getMessage(), e);
            }
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "..." : text;
    }
}
