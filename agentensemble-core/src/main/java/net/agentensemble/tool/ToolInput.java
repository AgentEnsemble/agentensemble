package net.agentensemble.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Java record class as the structured input type for a {@link TypedAgentTool}.
 *
 * <p>Apply this annotation to the record class that represents the full set of parameters
 * a tool accepts. The framework uses this as a signal during schema generation and
 * documentation, though the annotation itself is optional -- any record class works as
 * a typed tool input.
 *
 * <p>Use {@link ToolParam} on individual record components to provide descriptions and
 * required/optional semantics for each parameter.
 *
 * <p>Example:
 * <pre>
 * {@literal @}ToolInput(description = "Parameters for an HTTP request")
 * public record HttpRequestInput(
 *     {@literal @}ToolParam(description = "The URL to request") String url,
 *     {@literal @}ToolParam(description = "HTTP method", required = false) String method
 * ) {}
 * </pre>
 *
 * @see TypedAgentTool
 * @see ToolParam
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ToolInput {

    /**
     * A human-readable description of the input type as a whole.
     * This may be included in generated documentation.
     *
     * @return the description; empty string by default
     */
    String description() default "";
}
