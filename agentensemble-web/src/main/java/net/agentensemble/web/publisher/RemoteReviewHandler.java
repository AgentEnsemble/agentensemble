package net.agentensemble.web.publisher;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ReviewHandler} implementation for publisher-mode dashboards.
 *
 * <p>Mirrors the blocking semantics of the embedded {@code WebReviewHandler}, but routes the
 * review gate through a {@link LiveEventPublisher} instead of broadcasting to local WebSocket
 * sessions. The decision returns over the publisher's review reverse channel.
 *
 * <ol>
 *   <li>Generate a unique {@code reviewId}.</li>
 *   <li>Register a {@link CompletableFuture} for the decision payload.</li>
 *   <li>Publish a {@link ReviewRequestedMessage} via the sink so the hub broadcasts it to
 *       all connected browsers (wrapped in a {@code LiveEventEnvelope}).</li>
 *   <li>Block on the future for the configured timeout.</li>
 *   <li>The hub-side {@code LiveEventHub} routes the browser's {@code review_decision} back
 *       to this publisher; the publisher's subscriber completes the matching future via
 *       {@link #resolveDecision(String, String)}.</li>
 *   <li>Map the decision to a {@link ReviewDecision}.</li>
 * </ol>
 *
 * <p>The constructor wires {@link LiveEventPublisher#subscribeToReviewDecisions} so this handler
 * receives all hub-originated decisions for the publisher.
 *
 * <p>Thread-safe.
 */
public final class RemoteReviewHandler implements ReviewHandler {

    private static final Logger log = LoggerFactory.getLogger(RemoteReviewHandler.class);

    private final LiveEventPublisher publisher;
    private final MessageSerializer serializer;
    private final Duration reviewTimeout;
    private final OnTimeoutAction onTimeout;
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public RemoteReviewHandler(
            LiveEventPublisher publisher,
            MessageSerializer serializer,
            Duration reviewTimeout,
            OnTimeoutAction onTimeout) {
        this.publisher = publisher;
        this.serializer = serializer;
        this.reviewTimeout = reviewTimeout;
        this.onTimeout = onTimeout;
        publisher.subscribeToReviewDecisions(decision -> resolveDecision(decision.reviewId(), decision.decisionJson()));
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        String reviewId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(reviewId, future);

        Duration effectiveTimeout = request.timeout() != null ? request.timeout() : reviewTimeout;
        OnTimeoutAction effectiveOnTimeout = request.onTimeoutAction() != null ? request.onTimeoutAction() : onTimeout;
        try {
            ReviewRequestedMessage msg = new ReviewRequestedMessage(
                    reviewId,
                    request.taskDescription(),
                    request.taskOutput() != null ? request.taskOutput() : "",
                    request.timing() != null ? request.timing().name() : "AFTER_EXECUTION",
                    request.prompt(),
                    effectiveTimeout.toMillis(),
                    effectiveOnTimeout.name(),
                    request.requiredRole());
            publisher.accept(serializer.toJson(msg));
        } catch (RuntimeException e) {
            log.warn("Failed to publish review_requested {}: {}", reviewId, e.getMessage(), e);
        }

        try {
            String raw;
            if (effectiveTimeout.isZero()) {
                raw = future.get();
            } else {
                raw = future.get(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (raw == null || raw.isEmpty()) {
                return applyTimeoutAction(request, effectiveTimeout, effectiveOnTimeout);
            }
            ReviewDecisionMessage decision = serializer.fromJson(raw, ReviewDecisionMessage.class);
            return mapDecision(decision);
        } catch (TimeoutException e) {
            log.info("Remote review {} timed out after {}", reviewId, effectiveTimeout);
            pending.remove(reviewId);
            return applyTimeoutAction(request, effectiveTimeout, effectiveOnTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Remote review {} interrupted", reviewId);
            pending.remove(reviewId);
            return ReviewDecision.exitEarly();
        } catch (ExecutionException e) {
            log.warn("Remote review {} future completed exceptionally", reviewId, e);
            pending.remove(reviewId);
            return applyTimeoutAction(request, effectiveTimeout, effectiveOnTimeout);
        }
    }

    private void resolveDecision(String reviewId, String json) {
        CompletableFuture<String> future = pending.remove(reviewId);
        if (future != null) {
            future.complete(json);
        } else {
            log.debug("Received review decision for unknown reviewId {}; ignoring", reviewId);
        }
    }

    private ReviewDecision mapDecision(ReviewDecisionMessage decision) {
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

    private ReviewDecision applyTimeoutAction(
            ReviewRequest request, Duration effectiveTimeout, OnTimeoutAction effectiveOnTimeout) {
        return switch (effectiveOnTimeout) {
            case CONTINUE -> ReviewDecision.continueExecution();
            case EXIT_EARLY -> ReviewDecision.exitEarlyTimeout();
            case FAIL -> throw new ReviewTimeoutException(
                    "Remote review timed out after " + effectiveTimeout + " on task: " + request.taskDescription());
        };
    }
}
