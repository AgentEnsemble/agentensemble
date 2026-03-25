package net.agentensemble.format;

/**
 * Factory for obtaining {@link ContextFormatter} instances based on {@link ContextFormat}.
 *
 * <p>Usage:
 * <pre>
 * ContextFormatter formatter = ContextFormatters.forFormat(ContextFormat.JSON);
 * String encoded = formatter.format(myObject);
 * </pre>
 *
 * <p>The {@link ContextFormat#TOON} format requires {@code dev.toonformat:jtoon} on the
 * runtime classpath. Use {@link #isToonAvailable()} to check before requesting a TOON
 * formatter, or call {@link #forFormat(ContextFormat)} which throws a clear
 * {@link IllegalStateException} when JToon is missing.
 */
public final class ContextFormatters {

    /** Dependency instructions shown when JToon is missing. */
    static final String JTOON_MISSING_MESSAGE = "TOON context format requires the JToon library on the classpath.\n"
            + "Add to your build:\n"
            + "  Gradle: implementation(\"dev.toonformat:jtoon:1.0.9\")\n"
            + "  Maven:  <dependency><groupId>dev.toonformat</groupId>"
            + "<artifactId>jtoon</artifactId><version>1.0.9</version></dependency>";

    private static final ContextFormatter JSON_FORMATTER = new JsonContextFormatter();

    private ContextFormatters() {
        // utility class
    }

    /**
     * Obtain a {@link ContextFormatter} for the given format.
     *
     * @param format the desired serialization format; must not be null
     * @return a formatter instance; never null
     * @throws IllegalStateException if {@code format} is {@link ContextFormat#TOON}
     *                               and JToon is not on the classpath
     */
    public static ContextFormatter forFormat(ContextFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("ContextFormat must not be null");
        }
        return switch (format) {
            case JSON -> JSON_FORMATTER;
            case TOON -> {
                if (!isToonAvailable()) {
                    throw new IllegalStateException(JTOON_MISSING_MESSAGE);
                }
                yield new ToonContextFormatter();
            }
        };
    }

    /**
     * Check whether the JToon library is available on the runtime classpath.
     *
     * @return true if {@code dev.toonformat.jtoon.JToon} can be loaded
     */
    public static boolean isToonAvailable() {
        try {
            Class.forName("dev.toonformat.jtoon.JToon");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
