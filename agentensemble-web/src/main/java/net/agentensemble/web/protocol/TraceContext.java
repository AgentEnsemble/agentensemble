package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * W3C Trace Context fields for distributed tracing across ensemble boundaries.
 *
 * <p>Carries the {@code traceparent} and optional {@code tracestate} headers as defined
 * by the <a href="https://www.w3.org/TR/trace-context/">W3C Trace Context</a> specification.
 *
 * @param traceparent the W3C {@code traceparent} header value (e.g.,
 *                    {@code "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"})
 * @param tracestate  the optional W3C {@code tracestate} header value; may be null
 *
 * @see WorkRequest
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceContext(String traceparent, String tracestate) {}
