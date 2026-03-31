package net.agentensemble.execution;

/**
 * Per-run configuration overrides for an {@link net.agentensemble.Ensemble} run.
 *
 * <p>Fields are nullable; {@code null} means "inherit the ensemble-level default".
 * Use {@link #builder()} to construct an instance, or {@link #DEFAULT} when no overrides
 * are needed.
 *
 * <pre>{@code
 * // LLM sees full output but logs only show the first 500 chars
 * ensemble.run(RunOptions.builder()
 *     .maxToolOutputLength(-1)
 *     .toolLogTruncateLength(500)
 *     .build());
 *
 * // Full log output for debugging, even though the ensemble caps LLM output at 2000
 * ensemble.run(RunOptions.builder()
 *     .toolLogTruncateLength(-1)
 *     .build());
 * }</pre>
 */
public final class RunOptions {

    /** No overrides — all ensemble defaults apply. */
    public static final RunOptions DEFAULT = new RunOptions(null, null);

    private final Integer maxToolOutputLength;
    private final Integer toolLogTruncateLength;

    private RunOptions(Integer maxToolOutputLength, Integer toolLogTruncateLength) {
        this.maxToolOutputLength = maxToolOutputLength;
        this.toolLogTruncateLength = toolLogTruncateLength;
    }

    /**
     * Maximum number of characters of tool output sent to the LLM.
     * {@code -1} means unlimited. {@code null} means inherit the ensemble default.
     *
     * @return the override, or {@code null} if not set
     */
    public Integer getMaxToolOutputLength() {
        return maxToolOutputLength;
    }

    /**
     * Maximum number of characters of tool output written to log statements.
     * {@code -1} means full output. {@code 0} suppresses output content entirely.
     * {@code null} means inherit the ensemble default.
     *
     * @return the override, or {@code null} if not set
     */
    public Integer getToolLogTruncateLength() {
        return toolLogTruncateLength;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Integer maxToolOutputLength;
        private Integer toolLogTruncateLength;

        private Builder() {}

        /**
         * Override the maximum characters of tool output visible to the LLM.
         * {@code -1} means unlimited.
         */
        public Builder maxToolOutputLength(int maxToolOutputLength) {
            this.maxToolOutputLength = maxToolOutputLength;
            return this;
        }

        /**
         * Override the maximum characters of tool output emitted to log statements.
         * {@code -1} means full output; {@code 0} suppresses output content entirely.
         */
        public Builder toolLogTruncateLength(int toolLogTruncateLength) {
            this.toolLogTruncateLength = toolLogTruncateLength;
            return this;
        }

        public RunOptions build() {
            return new RunOptions(maxToolOutputLength, toolLogTruncateLength);
        }
    }
}
