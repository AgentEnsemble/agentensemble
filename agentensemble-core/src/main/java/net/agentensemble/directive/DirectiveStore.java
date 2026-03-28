package net.agentensemble.directive;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe store of active directives for an ensemble.
 *
 * <p>Uses {@link CopyOnWriteArrayList} for lock-free reads during prompt building.
 * Writes (add, remove, expire) are infrequent relative to reads, making COW the
 * ideal data structure.
 *
 * <p>Stale directives (past their {@link Directive#expiresAt()}) are lazily removed
 * on every read operation.
 */
public class DirectiveStore {

    private final CopyOnWriteArrayList<Directive> directives = new CopyOnWriteArrayList<>();

    /**
     * Add a directive to the store.
     *
     * @param directive the directive to add; must not be null
     * @throws IllegalArgumentException if directive is null
     */
    public void add(Directive directive) {
        if (directive == null) {
            throw new IllegalArgumentException("directive must not be null");
        }
        directives.add(directive);
    }

    /**
     * Remove a directive by its ID.
     *
     * @param directiveId the ID of the directive to remove
     */
    public void remove(String directiveId) {
        directives.removeIf(d -> d.id().equals(directiveId));
    }

    /**
     * Returns all active (non-expired) context directives.
     * Context directives have a {@code null} action and are injected into agent prompts.
     *
     * @return an immutable list of active context directives
     */
    public List<Directive> activeContextDirectives() {
        expireStale();
        return directives.stream()
                .filter(d -> !d.isExpired() && d.isContextDirective())
                .toList();
    }

    /**
     * Returns all active (non-expired) control plane directives.
     * Control plane directives have a non-null action and modify ensemble behavior.
     *
     * @return an immutable list of active control plane directives
     */
    public List<Directive> activeControlPlaneDirectives() {
        expireStale();
        return directives.stream()
                .filter(d -> !d.isExpired() && d.isControlPlaneDirective())
                .toList();
    }

    /**
     * Returns all active (non-expired) directives regardless of type.
     *
     * @return an immutable copy of all active directives
     */
    public List<Directive> all() {
        expireStale();
        return List.copyOf(directives);
    }

    /**
     * Remove all expired directives from the store.
     */
    public void expireStale() {
        directives.removeIf(Directive::isExpired);
    }
}
