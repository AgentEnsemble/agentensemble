package net.agentensemble.output;

/**
 * Result of a single structured output parse attempt.
 *
 * Returned by {@link StructuredOutputParser#parse(String, Class)} to
 * communicate success or failure without throwing exceptions in the hot
 * path of the retry loop.
 *
 * @param <T> the target type
 */
public final class ParseResult<T> {

    private final T value;
    private final String errorMessage;

    private ParseResult(T value, String errorMessage) {
        this.value = value;
        this.errorMessage = errorMessage;
    }

    /** Create a successful result carrying the parsed value. */
    public static <T> ParseResult<T> success(T value) {
        return new ParseResult<>(value, null);
    }

    /** Create a failure result carrying the error description. */
    public static <T> ParseResult<T> failure(String errorMessage) {
        return new ParseResult<>(null, errorMessage);
    }

    /** Returns {@code true} if parsing succeeded. */
    public boolean isSuccess() {
        return errorMessage == null;
    }

    /**
     * Returns the parsed value.
     * Only meaningful when {@link #isSuccess()} is {@code true}.
     */
    public T getValue() {
        return value;
    }

    /**
     * Returns the human-readable error message.
     * Only meaningful when {@link #isSuccess()} is {@code false}.
     */
    public String getErrorMessage() {
        return errorMessage;
    }
}
