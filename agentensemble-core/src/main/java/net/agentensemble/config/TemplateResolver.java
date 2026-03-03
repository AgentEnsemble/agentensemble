package net.agentensemble.config;

import net.agentensemble.exception.PromptTemplateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {variable} template placeholders in strings.
 *
 * Variables are denoted by {name} where name contains only word characters
 * (letters, digits, underscores). Escaped variables {{name}} are converted
 * to literal {name} without substitution.
 *
 * Example:
 * <pre>
 * String resolved = TemplateResolver.resolve(
 *     "Research {topic} developments in {year}",
 *     Map.of("topic", "AI agents", "year", "2026"));
 * // Result: "Research AI agents developments in 2026"
 * </pre>
 */
public final class TemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(TemplateResolver.class);

    /**
     * Sentinel prefix used to protect escaped {{ }} from being processed as variables.
     *
     * The value includes a long, UUID-like component to make accidental collisions with
     * user-provided template content extremely unlikely.
     */
    private static final String SENTINEL_PREFIX = "__AGENTENSEMBLE_ESCAPED_6f8b2c1e_17a3_4b9c_9a3b_42de8f8c9b2d_";
    private static final String SENTINEL_SUFFIX = "__";

    /** Matches {{word}} -- escaped variables. */
    private static final Pattern ESCAPED_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /** Matches {word} -- template variables. */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{(\\w+)}");

    /** Pre-compiled pattern to restore sentinel tokens back to literal {name}. */
    private static final Pattern SENTINEL_RESTORE_PATTERN = Pattern.compile(
            Pattern.quote(SENTINEL_PREFIX) + "(\\w+)" + Pattern.quote(SENTINEL_SUFFIX));

    /** Maximum length of template shown in error messages. */
    private static final int ERROR_TEMPLATE_MAX_LENGTH = 100;

    private TemplateResolver() {
        // Utility class -- not instantiable
    }

    /**
     * Resolve template variables in the given string.
     *
     * @param template a string with {variable} placeholders; null is returned as-is
     * @param inputs map of variable names to replacement values; null treated as empty
     * @return the resolved string
     * @throws PromptTemplateException if any unescaped variables are not found in inputs
     */
    public static String resolve(String template, Map<String, String> inputs) {
        if (template == null) {
            return null;
        }
        if (template.isEmpty() || template.isBlank()) {
            return template;
        }

        Map<String, String> effectiveInputs = inputs != null ? inputs : Map.of();

        log.debug("Resolving template ({} chars) with {} input variables",
                template.length(), effectiveInputs.size());

        // Step 1: Protect escaped {{ }} by replacing with sentinels
        String working = ESCAPED_PATTERN.matcher(template)
                .replaceAll(m -> SENTINEL_PREFIX + m.group(1) + SENTINEL_SUFFIX);

        // Step 2: Find all unescaped {variable} references (preserving order, deduplicating)
        Matcher matcher = VARIABLE_PATTERN.matcher(working);
        LinkedHashSet<String> foundVariables = new LinkedHashSet<>();
        while (matcher.find()) {
            foundVariables.add(matcher.group(1));
        }

        // Step 3: Collect missing variables (report ALL, not just the first)
        List<String> missingVariables = new ArrayList<>();
        for (String name : foundVariables) {
            if (!effectiveInputs.containsKey(name)) {
                missingVariables.add(name);
            }
        }

        if (!missingVariables.isEmpty()) {
            String truncated = template.length() > ERROR_TEMPLATE_MAX_LENGTH
                    ? template.substring(0, ERROR_TEMPLATE_MAX_LENGTH) + "..."
                    : template;
            throw new PromptTemplateException(
                    "Missing template variables: " + missingVariables
                    + ". Provide them in ensemble.run(inputs). Template: '" + truncated + "'",
                    missingVariables,
                    template);
        }

        // Step 4: Replace each {variable} with its value
        for (String name : foundVariables) {
            String value = effectiveInputs.get(name);
            working = working.replace("{" + name + "}", value != null ? value : "");
        }

        // Step 5: Restore escaped sentinels as literal {name}
        working = SENTINEL_RESTORE_PATTERN.matcher(working)
                .replaceAll(m -> "{" + m.group(1) + "}");

        log.debug("Resolved {} variables in template", foundVariables.size());

        return working;
    }
}
