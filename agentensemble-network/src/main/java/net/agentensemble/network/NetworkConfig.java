package net.agentensemble.network;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.agentensemble.network.federation.FederationConfig;
import net.agentensemble.network.memory.SharedMemory;

/**
 * Configuration for cross-ensemble network communication.
 *
 * <p>Maps ensemble names to their WebSocket URLs and provides default timeout values.
 *
 * <pre>
 * NetworkConfig config = NetworkConfig.builder()
 *     .ensemble("kitchen", "ws://kitchen:7329/ws")
 *     .ensemble("maintenance", "ws://maintenance:7329/ws")
 *     .defaultTaskTimeout(Duration.ofMinutes(15))
 *     .build();
 * </pre>
 *
 * @param ensembleUrls         mapping from ensemble name to WebSocket URL
 * @param defaultConnectTimeout connect timeout for new WebSocket connections
 * @param defaultTaskTimeout    default execution timeout for {@link NetworkTask}
 * @param defaultToolTimeout    default execution timeout for {@link NetworkTool}
 * @param sharedMemories       named {@link SharedMemory} instances available at the network level
 * @param federationConfig     optional federation configuration for cross-realm capability sharing;
 *                             {@code null} means federation is disabled
 */
public record NetworkConfig(
        Map<String, String> ensembleUrls,
        Duration defaultConnectTimeout,
        Duration defaultTaskTimeout,
        Duration defaultToolTimeout,
        Map<String, SharedMemory> sharedMemories,
        FederationConfig federationConfig) {

    /** Default connect timeout: 10 seconds. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Default task execution timeout: 30 minutes. */
    public static final Duration DEFAULT_TASK_TIMEOUT = Duration.ofMinutes(30);

    /** Default tool execution timeout: 30 seconds. */
    public static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Compact constructor with defaults.
     */
    public NetworkConfig {
        ensembleUrls = ensembleUrls != null ? Map.copyOf(ensembleUrls) : Map.of();
        if (defaultConnectTimeout == null) {
            defaultConnectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (defaultTaskTimeout == null) {
            defaultTaskTimeout = DEFAULT_TASK_TIMEOUT;
        }
        if (defaultToolTimeout == null) {
            defaultToolTimeout = DEFAULT_TOOL_TIMEOUT;
        }
        sharedMemories = sharedMemories != null ? Map.copyOf(sharedMemories) : Map.of();
    }

    /**
     * Look up the WebSocket URL for the given ensemble name.
     *
     * @param ensembleName the ensemble name to look up
     * @return the WebSocket URL, or null if not configured
     */
    public String urlFor(String ensembleName) {
        return ensembleUrls.get(ensembleName);
    }

    /**
     * Returns a new builder.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link NetworkConfig}.
     */
    public static final class Builder {
        private final Map<String, String> urls = new HashMap<>();
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration taskTimeout = DEFAULT_TASK_TIMEOUT;
        private Duration toolTimeout = DEFAULT_TOOL_TIMEOUT;
        private final Map<String, SharedMemory> sharedMemories = new HashMap<>();
        private FederationConfig federationConfig;

        private Builder() {}

        /**
         * Register an ensemble's WebSocket URL.
         *
         * @param name the ensemble name
         * @param wsUrl the WebSocket URL (e.g., {@code "ws://kitchen:7329/ws"})
         * @return this builder
         */
        public Builder ensemble(String name, String wsUrl) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(wsUrl, "wsUrl must not be null");
            urls.put(name, wsUrl);
            return this;
        }

        /**
         * Set the default connect timeout.
         *
         * @param timeout the connect timeout; must not be null
         * @return this builder
         */
        public Builder defaultConnectTimeout(Duration timeout) {
            this.connectTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Set the default task execution timeout.
         *
         * @param timeout the task timeout; must not be null
         * @return this builder
         */
        public Builder defaultTaskTimeout(Duration timeout) {
            this.taskTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Set the default tool execution timeout.
         *
         * @param timeout the tool timeout; must not be null
         * @return this builder
         */
        public Builder defaultToolTimeout(Duration timeout) {
            this.toolTimeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Register a named {@link SharedMemory} instance.
         *
         * @param name         the shared memory name; must not be null
         * @param sharedMemory the shared memory instance; must not be null
         * @return this builder
         */
        public Builder sharedMemory(String name, SharedMemory sharedMemory) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(sharedMemory, "sharedMemory must not be null");
            sharedMemories.put(name, sharedMemory);
            return this;
        }

        /**
         * Set the federation configuration for cross-realm capability sharing.
         *
         * @param federationConfig the federation config; may be null to disable federation
         * @return this builder
         */
        public Builder federationConfig(FederationConfig federationConfig) {
            this.federationConfig = federationConfig;
            return this;
        }

        /**
         * Build the configuration.
         *
         * @return a new NetworkConfig
         */
        public NetworkConfig build() {
            return new NetworkConfig(urls, connectTimeout, taskTimeout, toolTimeout, sharedMemories, federationConfig);
        }
    }
}
