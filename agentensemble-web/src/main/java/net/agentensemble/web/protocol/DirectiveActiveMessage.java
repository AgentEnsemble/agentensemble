package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Server-to-client broadcast indicating that a new directive is now active.
 *
 * <p>Sent to all connected clients when a directive is added to the ensemble's
 * {@link net.agentensemble.directive.DirectiveStore}.
 *
 * @param directiveId the unique ID of the active directive
 * @param from        human-readable issuer name
 * @param content     the directive text
 * @param action      optional control plane action name
 * @param value       optional control plane action value
 * @param expiresAt   optional ISO-8601 expiration timestamp
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DirectiveActiveMessage(
        String directiveId, String from, String content, String action, String value, String expiresAt)
        implements ServerMessage {}
