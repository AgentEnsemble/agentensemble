package net.agentensemble.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a single parameter on a {@link ToolInput}-annotated record.
 *
 * <p>Apply this annotation to record components to provide:
 * <ul>
 *   <li>A description that is shown to the LLM in the tool's JSON Schema
 *   <li>A required/optional designation that controls the {@code required} array
 *       in the generated schema
 * </ul>
 *
 * <p>Parameters are required by default. Mark optional parameters with
 * {@code required = false}.
 *
 * <p>Example:
 * <pre>
 * {@literal @}ToolInput(description = "Parameters for an HTTP request")
 * public record HttpRequestInput(
 *     {@literal @}ToolParam(description = "The URL to request") String url,
 *     {@literal @}ToolParam(description = "HTTP method (GET, POST, PUT, DELETE)", required = false) String method,
 *     {@literal @}ToolParam(description = "Request headers as key-value pairs", required = false) Map{@literal <}String, String{@literal >} headers,
 *     {@literal @}ToolParam(description = "Request body text", required = false) String body
 * ) {}
 * </pre>
 *
 * @see ToolInput
 * @see TypedAgentTool
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface ToolParam {

    /**
     * A human-readable description of this parameter.
     * Included in the generated JSON Schema and shown to the LLM.
     *
     * @return the description; empty string by default
     */
    String description() default "";

    /**
     * Whether this parameter is required.
     *
     * <p>Required parameters appear in the {@code required} array of the generated
     * JSON Schema. Optional parameters do not.
     *
     * @return {@code true} if the parameter must be provided; {@code false} if optional
     */
    boolean required() default true;
}
