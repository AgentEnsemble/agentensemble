package net.agentensemble.network.federation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for federation -- cross-realm discovery and capability sharing.
 *
 * <pre>
 * FederationConfig config = FederationConfig.builder()
 *     .localRealm("hotel-downtown")
 *     .federationName("Hotel Chain")
 *     .realm("hotel-airport", "hotel-airport-ns")
 *     .realm("hotel-beach", "hotel-beach-ns")
 *     .build();
 * </pre>
 *
 * @param localRealm     the realm of this ensemble instance
 * @param federationName the logical name of the federation group
 * @param realms         known realms in the federation
 */
public record FederationConfig(String localRealm, String federationName, Map<String, RealmInfo> realms) {

    public FederationConfig {
        Objects.requireNonNull(localRealm, "localRealm must not be null");
        Objects.requireNonNull(federationName, "federationName must not be null");
        realms = realms != null ? Map.copyOf(realms) : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String localRealm;
        private String federationName;
        private final Map<String, RealmInfo> realms = new HashMap<>();

        private Builder() {}

        public Builder localRealm(String localRealm) {
            this.localRealm = Objects.requireNonNull(localRealm);
            return this;
        }

        public Builder federationName(String federationName) {
            this.federationName = Objects.requireNonNull(federationName);
            return this;
        }

        public Builder realm(String name, String namespace) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(namespace);
            realms.put(name, new RealmInfo(name, namespace));
            return this;
        }

        public FederationConfig build() {
            Objects.requireNonNull(localRealm, "localRealm is required");
            Objects.requireNonNull(federationName, "federationName is required");
            return new FederationConfig(localRealm, federationName, realms);
        }
    }
}
