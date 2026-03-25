package net.agentensemble.format;

/**
 * Serialization format for structured data included in LLM prompts.
 *
 * <p>JSON is the default and is always available. TOON provides 30-60%
 * token reduction but requires the {@code dev.toonformat:jtoon} library
 * on the classpath.
 *
 * @see ContextFormatter
 * @see ContextFormatters
 */
public enum ContextFormat {

    /** Standard JSON serialization (default). Always available. */
    JSON,

    /**
     * TOON (Token-Oriented Object Notation) serialization.
     *
     * <p>Requires {@code dev.toonformat:jtoon} on the runtime classpath.
     * If selected without JToon present, {@code Ensemble.build()} fails
     * with a clear error message including dependency coordinates.
     */
    TOON
}
