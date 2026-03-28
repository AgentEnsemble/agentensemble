package net.agentensemble.review;

/**
 * SPI for sending out-of-band notifications when a review gate is pending.
 *
 * <p>When a review gate fires and no qualified human is connected to the dashboard,
 * the configured {@code ReviewNotifier} can alert humans via external channels
 * (Slack, email, webhook, etc.) so they know a review is waiting.
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link #slack(String)} -- sends a message to a Slack incoming webhook</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>
 * ReviewNotifier notifier = ReviewNotifier.slack("https://hooks.slack.com/services/...");
 * </pre>
 */
public interface ReviewNotifier {

    /**
     * Send a notification that a review is pending.
     *
     * <p>Implementations should be non-blocking or use a short timeout so that the
     * notification does not delay the review gate.
     *
     * @param request the review request; never null
     */
    void notifyReviewPending(ReviewRequest request);

    /**
     * Create a {@link ReviewNotifier} that sends notifications to a Slack incoming webhook.
     *
     * <p>The notification includes the task description, prompt, required role, and
     * timeout information formatted as a Slack message.
     *
     * @param webhookUrl the Slack incoming webhook URL; must not be null
     * @return a Slack-based ReviewNotifier
     */
    static ReviewNotifier slack(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("webhookUrl must not be null or blank");
        }
        return new SlackReviewNotifier(webhookUrl);
    }
}
