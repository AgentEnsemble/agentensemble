package net.agentensemble.review;

/**
 * Thrown by a {@link ReviewHandler} when a review gate times out and the configured
 * {@link OnTimeoutAction} is {@link OnTimeoutAction#FAIL}.
 *
 * <p>This exception propagates as a task execution failure. The caller should handle
 * it as an unrecoverable review timeout.
 */
public class ReviewTimeoutException extends RuntimeException {

    /**
     * Construct a ReviewTimeoutException with a detail message.
     *
     * @param message a description of the timeout context
     */
    public ReviewTimeoutException(String message) {
        super(message);
    }

    /**
     * Construct a ReviewTimeoutException with a detail message and cause.
     *
     * @param message a description of the timeout context
     * @param cause   the underlying exception, if any
     */
    public ReviewTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
