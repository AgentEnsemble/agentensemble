package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Client-to-server message for injecting a human directive into an ensemble.
 *
 * <p>Context directives ({@code action == null}) are injected into future task prompts.
 * Control plane directives ({@code action != null}) modify ensemble behavior at runtime.
 *
 * @param to      target ensemble name (reserved for multi-ensemble routing)
 * @param from    human-readable issuer name
 * @param content the directive text
 * @param action  optional control plane action name
 * @param value   optional control plane action value
 * @param ttl     optional time-to-live as an ISO-8601 duration string (e.g. "PT10M")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectiveMessage(String to, String from, String content, String action, String value, String ttl)
        implements ClientMessage {}
