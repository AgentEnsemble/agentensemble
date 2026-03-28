package net.agentensemble.directive;

import java.time.Instant;

/**
 * A directive injected by a human or automated policy into an ensemble's context.
 *
 * <p>Context directives ({@code action == null}) are injected into future task prompts
 * as an {@code ## Active Directives} section, giving agents real-time human guidance
 * without stopping execution.
 *
 * <p>Control plane directives ({@code action != null}) modify ensemble behavior at
 * runtime (e.g., switching model tiers, applying operational profiles).
 *
 * @param id        unique identifier for this directive
 * @param from      human-readable name of the directive issuer
 * @param content   the directive text injected into prompts (context directives)
 * @param action    control plane action name; {@code null} for context directives
 * @param value     control plane action value; {@code null} for context directives
 * @param createdAt timestamp when the directive was created
 * @param expiresAt optional expiration timestamp; {@code null} means no expiry
 */
public record Directive(
        String id, String from, String content, String action, String value, Instant createdAt, Instant expiresAt) {

    /**
     * Returns {@code true} if this directive has an expiry time that is in the past.
     *
     * @return true when expired
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Returns {@code true} if this is a context directive (no control plane action).
     * Context directives are injected into agent prompts.
     *
     * @return true for context directives
     */
    public boolean isContextDirective() {
        return action == null;
    }

    /**
     * Returns {@code true} if this is a control plane directive.
     * Control plane directives modify ensemble behavior at runtime.
     *
     * @return true for control plane directives
     */
    public boolean isControlPlaneDirective() {
        return action != null;
    }
}
