package net.agentensemble.review;

import java.net.URI;

/**
 * A stub {@link ReviewHandler} that is intended to POST review requests to a remote URL
 * for webhook-based human review workflows.
 *
 * <p>This implementation is a design placeholder. It always throws
 * {@link UnsupportedOperationException}. A full implementation is planned for a
 * future release.
 *
 * <p>Obtain an instance via {@link ReviewHandler#web(URI)}.
 */
final class WebReviewHandler implements ReviewHandler {

    private final URI callbackUrl;

    /**
     * Construct with the given callback URL.
     *
     * @param callbackUrl the URL to POST review requests to; must not be null
     */
    WebReviewHandler(URI callbackUrl) {
        if (callbackUrl == null) {
            throw new IllegalArgumentException("callbackUrl must not be null");
        }
        this.callbackUrl = callbackUrl;
    }

    /**
     * Returns the configured callback URL.
     *
     * @return the callback URL; never null
     */
    URI getCallbackUrl() {
        return callbackUrl;
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        throw new UnsupportedOperationException("WebReviewHandler is a design placeholder and is not yet implemented. "
                + "Callback URL: "
                + callbackUrl
                + ". "
                + "A webhook-based review handler is planned for a future release.");
    }
}
