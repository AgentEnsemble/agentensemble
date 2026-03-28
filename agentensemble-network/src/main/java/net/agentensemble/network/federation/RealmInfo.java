package net.agentensemble.network.federation;

import java.util.Objects;

/**
 * Identity of a realm -- a namespace-level discovery and trust boundary.
 *
 * <p>In Kubernetes deployments, each realm typically maps to a namespace.
 *
 * @param name      the realm name (e.g., {@code "hotel-downtown"})
 * @param namespace the K8s namespace (e.g., {@code "hotel-downtown-ns"})
 */
public record RealmInfo(String name, String namespace) {

    public RealmInfo {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(namespace, "namespace must not be null");
    }
}
