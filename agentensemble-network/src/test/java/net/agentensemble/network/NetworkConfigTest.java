package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkConfig}.
 */
class NetworkConfigTest {

    @Test
    void builder_createsConfigWithEnsembleUrls() {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .ensemble("maintenance", "ws://maintenance:7329/ws")
                .build();

        assertThat(config.ensembleUrls()).hasSize(2);
        assertThat(config.ensembleUrls()).containsEntry("kitchen", "ws://kitchen:7329/ws");
        assertThat(config.ensembleUrls()).containsEntry("maintenance", "ws://maintenance:7329/ws");
    }

    @Test
    void defaultTimeouts_areApplied() {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .build();

        assertThat(config.defaultConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.defaultTaskTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.defaultToolTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void customTimeouts_overrideDefaults() {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .defaultConnectTimeout(Duration.ofSeconds(5))
                .defaultTaskTimeout(Duration.ofMinutes(15))
                .defaultToolTimeout(Duration.ofSeconds(60))
                .build();

        assertThat(config.defaultConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.defaultTaskTimeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(config.defaultToolTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void urlFor_returnsCorrectUrl() {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .ensemble("maintenance", "ws://maintenance:7329/ws")
                .build();

        assertThat(config.urlFor("kitchen")).isEqualTo("ws://kitchen:7329/ws");
        assertThat(config.urlFor("maintenance")).isEqualTo("ws://maintenance:7329/ws");
    }

    @Test
    void urlFor_returnsNullForUnknownEnsemble() {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .build();

        assertThat(config.urlFor("unknown")).isNull();
    }

    @Test
    void emptyConfig_hasEmptyUrlMap() {
        NetworkConfig config = NetworkConfig.builder().build();

        assertThat(config.ensembleUrls()).isEmpty();
    }

    @Test
    void defaultConstants_matchExpectedValues() {
        assertThat(NetworkConfig.DEFAULT_CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
        assertThat(NetworkConfig.DEFAULT_TASK_TIMEOUT).isEqualTo(Duration.ofMinutes(30));
        assertThat(NetworkConfig.DEFAULT_TOOL_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void ensembleUrls_isImmutable() {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("kitchen", "ws://kitchen:7329/ws")
                .build();

        assertThat(config.ensembleUrls()).isUnmodifiable();
    }

    @Test
    void recordConstructor_handlesNullTimeoutsWithDefaults() {
        NetworkConfig config = new NetworkConfig(null, null, null, null);

        assertThat(config.ensembleUrls()).isEmpty();
        assertThat(config.defaultConnectTimeout()).isEqualTo(NetworkConfig.DEFAULT_CONNECT_TIMEOUT);
        assertThat(config.defaultTaskTimeout()).isEqualTo(NetworkConfig.DEFAULT_TASK_TIMEOUT);
        assertThat(config.defaultToolTimeout()).isEqualTo(NetworkConfig.DEFAULT_TOOL_TIMEOUT);
    }
}
