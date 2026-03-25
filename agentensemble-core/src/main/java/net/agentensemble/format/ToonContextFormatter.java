package net.agentensemble.format;

import dev.toonformat.jtoon.JToon;

/**
 * TOON implementation of {@link ContextFormatter}.
 *
 * <p>Delegates to {@link JToon#encode(Object)} and {@link JToon#encodeJson(String)}
 * from the {@code dev.toonformat:jtoon} library. This class is only instantiated
 * when JToon is confirmed to be on the classpath (checked by {@link ContextFormatters}).
 *
 * <p>Package-private -- users obtain a formatter via
 * {@link ContextFormatters#forFormat(ContextFormat)}.
 */
final class ToonContextFormatter implements ContextFormatter {

    @Override
    public String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            // Plain strings are not structured data -- return as-is
            return s;
        }
        return JToon.encode(value);
    }

    @Override
    public String formatJson(String json) {
        if (json == null) {
            return "null";
        }
        if (json.isBlank()) {
            return json;
        }
        try {
            return JToon.encodeJson(json);
        } catch (IllegalArgumentException e) {
            // If the string is not valid JSON, return it as-is
            return json;
        }
    }
}
