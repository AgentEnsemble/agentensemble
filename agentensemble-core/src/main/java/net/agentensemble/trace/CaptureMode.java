package net.agentensemble.trace;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controls the depth of data collection during an ensemble run.
 *
 * <p>Set programmatically on the ensemble builder:
 * <pre>
 * Ensemble.builder()
 *     .captureMode(CaptureMode.FULL)
 *     .build();
 * </pre>
 *
 * <p>Or activate without any code change via a JVM system property:
 * <pre>
 * java -Dagentensemble.captureMode=FULL -jar my-app.jar
 * </pre>
 *
 * <p>Or via an environment variable:
 * <pre>
 * AGENTENSEMBLE_CAPTURE_MODE=STANDARD java -jar my-app.jar
 * </pre>
 *
 * <p>Resolution order (first wins):
 * <ol>
 *   <li>Explicit {@code .captureMode()} on the builder (when not {@link #OFF})</li>
 *   <li>JVM system property {@code agentensemble.captureMode}</li>
 *   <li>Environment variable {@code AGENTENSEMBLE_CAPTURE_MODE}</li>
 *   <li>Default: {@link #OFF}</li>
 * </ol>
 *
 * <h2>Levels</h2>
 *
 * <table>
 *   <caption>CaptureMode levels</caption>
 *   <tr><th>Level</th><th>Captured</th><th>Trace size</th></tr>
 *   <tr>
 *     <td>{@link #OFF}</td>
 *     <td>Prompts, tool args/results, timing, token counts</td>
 *     <td>Minimal</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #STANDARD}</td>
 *     <td>Everything in OFF plus: full LLM message history per iteration,
 *         memory operation counts wired into trace</td>
 *     <td>Moderate</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #FULL}</td>
 *     <td>Everything in STANDARD plus: auto-export to {@code ./traces/},
 *         enriched tool I/O with parsed JSON arguments</td>
 *     <td>Larger</td>
 *   </tr>
 * </table>
 *
 * <p>{@link #OFF} has zero performance impact beyond what the base trace infrastructure
 * already adds (object allocation only).
 *
 * <p>{@link CaptureMode} is orthogonal to {@code verbose} (logging) and
 * {@code traceExporter} (export destination); all three can be combined independently.
 */
public enum CaptureMode {

    /**
     * Default mode. Captures prompts, tool arguments and results, timing, and token counts.
     * No message history, no parsed tool input, no automatic export.
     */
    OFF,

    /**
     * Captures everything in {@link #OFF} plus:
     * <ul>
     *   <li>Full LLM message history per ReAct iteration (system, user, assistant, tool result)</li>
     *   <li>Memory operation counts wired into the task trace</li>
     * </ul>
     * Message history enables replay of the exact conversation the LLM had, step by step.
     * Memory counts track STM writes, LTM stores, LTM retrievals, and entity lookups.
     */
    STANDARD,

    /**
     * Captures everything in {@link #STANDARD} plus:
     * <ul>
     *   <li>Automatic JSON export to {@code ./traces/} after each run (when no
     *       explicit {@code traceExporter} is configured)</li>
     *   <li>Enriched tool I/O: {@code ToolCallTrace.parsedInput} is populated with a
     *       structured {@code Map<String,Object>} representation of the tool's JSON arguments</li>
     * </ul>
     */
    FULL;

    private static final Logger log = LoggerFactory.getLogger(CaptureMode.class);

    /** JVM system property name for activating capture mode without code changes. */
    public static final String SYSTEM_PROPERTY = "agentensemble.captureMode";

    /** Environment variable name for activating capture mode without code changes. */
    public static final String ENV_VAR = "AGENTENSEMBLE_CAPTURE_MODE";

    /**
     * Return true if this level is at least as detailed as {@code other}.
     *
     * <p>For example, {@code FULL.isAtLeast(STANDARD)} returns {@code true}.
     * {@code OFF.isAtLeast(STANDARD)} returns {@code false}.
     *
     * @param other the minimum level to compare against
     * @return true when {@code this.ordinal() >= other.ordinal()}
     */
    public boolean isAtLeast(CaptureMode other) {
        return this.compareTo(other) >= 0;
    }

    /**
     * Resolve the effective {@link CaptureMode} using the full resolution chain.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>When {@code programmatic} is not {@link #OFF}, return it immediately --
     *       the programmer's explicit choice always wins.</li>
     *   <li>JVM system property {@value #SYSTEM_PROPERTY} (case-insensitive enum name)</li>
     *   <li>Environment variable {@value #ENV_VAR} (case-insensitive enum name)</li>
     *   <li>{@link #OFF}</li>
     * </ol>
     *
     * <p>Unknown or malformed values in the system property or environment variable are
     * logged at WARN level and ignored; resolution continues to the next source.
     *
     * @param programmatic the value set on the ensemble builder; must not be {@code null}
     * @return the resolved effective capture mode
     */
    public static CaptureMode resolve(CaptureMode programmatic) {
        if (programmatic == null) {
            return OFF;
        }

        // Step 1: programmer's explicit choice wins when not OFF
        if (programmatic != OFF) {
            return programmatic;
        }

        // Step 2: JVM system property
        String sysProp = System.getProperty(SYSTEM_PROPERTY);
        if (sysProp != null && !sysProp.isBlank()) {
            CaptureMode fromProp = parse(sysProp.trim(), "system property " + SYSTEM_PROPERTY);
            if (fromProp != null) {
                log.debug("CaptureMode activated via system property {}={}", SYSTEM_PROPERTY, fromProp);
                return fromProp;
            }
        }

        // Step 3: environment variable
        String envVal = System.getenv(ENV_VAR);
        if (envVal != null && !envVal.isBlank()) {
            CaptureMode fromEnv = parse(envVal.trim(), "environment variable " + ENV_VAR);
            if (fromEnv != null) {
                log.debug("CaptureMode activated via environment variable {}={}", ENV_VAR, fromEnv);
                return fromEnv;
            }
        }

        // Step 4: default
        return OFF;
    }

    private static CaptureMode parse(String value, String source) {
        try {
            return CaptureMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn(
                    "Unrecognised CaptureMode value '{}' in {} -- ignoring. Valid values: OFF, STANDARD, FULL",
                    value,
                    source);
            return null;
        }
    }
}
