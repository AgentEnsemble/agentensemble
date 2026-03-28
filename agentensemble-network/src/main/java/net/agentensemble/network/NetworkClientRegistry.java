package net.agentensemble.network;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of {@link NetworkClient} instances, keyed by ensemble name.
 *
 * <p>Provides lazy creation and connection reuse: the first call to
 * {@link #getOrCreate(String)} for a given ensemble name creates and caches a new
 * {@code NetworkClient}. Subsequent calls return the same instance.
 *
 * <p>Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}.
 *
 * <pre>
 * NetworkConfig config = NetworkConfig.builder()
 *     .ensemble("kitchen", "ws://kitchen:7329/ws")
 *     .build();
 *
 * try (NetworkClientRegistry registry = new NetworkClientRegistry(config)) {
 *     NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);
 *     // ... use task ...
 * }
 * </pre>
 */
public class NetworkClientRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkClientRegistry.class);

    private final NetworkConfig config;
    private final ConcurrentHashMap<String, NetworkClient> clients = new ConcurrentHashMap<>();

    /**
     * Create a registry backed by the given configuration.
     *
     * @param config the network configuration; must not be null
     */
    public NetworkClientRegistry(NetworkConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * Get or create a {@link NetworkClient} for the given ensemble name.
     *
     * <p>If a client for this ensemble already exists, it is returned. Otherwise, a new
     * client is created using the URL from the {@link NetworkConfig}. The connection is
     * established lazily on the first {@link NetworkClient#send} call.
     *
     * @param ensembleName the ensemble name
     * @return the cached or newly created client; never null
     * @throws IllegalArgumentException if no URL is configured for the ensemble name
     */
    public NetworkClient getOrCreate(String ensembleName) {
        return clients.computeIfAbsent(ensembleName, name -> {
            String url = config.urlFor(name);
            if (url == null) {
                throw new IllegalArgumentException("No WebSocket URL configured for ensemble '" + name + "'. "
                        + "Add it via NetworkConfig.builder().ensemble(\"" + name + "\", \"ws://...\").");
            }
            return new NetworkClient(name, url, config.defaultConnectTimeout());
        });
    }

    /**
     * Returns the number of clients currently in the registry.
     */
    public int size() {
        return clients.size();
    }

    /**
     * Close all clients in the registry and clear the cache.
     */
    @Override
    public void close() {
        clients.forEach((name, client) -> {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing client for ensemble '{}': {}", name, e.getMessage());
            }
        });
        clients.clear();
    }
}
