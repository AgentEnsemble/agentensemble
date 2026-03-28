package net.agentensemble.network.federation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FederationConfig}.
 */
class FederationConfigTest {

    @Test
    void builder_constructsValidConfig() {
        FederationConfig config = FederationConfig.builder()
                .localRealm("hotel-downtown")
                .federationName("Hotel Chain")
                .realm("hotel-airport", "hotel-airport-ns")
                .realm("hotel-beach", "hotel-beach-ns")
                .build();

        assertThat(config.localRealm()).isEqualTo("hotel-downtown");
        assertThat(config.federationName()).isEqualTo("Hotel Chain");
        assertThat(config.realms()).hasSize(2);
        assertThat(config.realms()).containsKey("hotel-airport");
        assertThat(config.realms()).containsKey("hotel-beach");
        assertThat(config.realms().get("hotel-airport").namespace()).isEqualTo("hotel-airport-ns");
        assertThat(config.realms().get("hotel-beach").namespace()).isEqualTo("hotel-beach-ns");
    }

    @Test
    void localRealm_required() {
        assertThatThrownBy(() ->
                        FederationConfig.builder().federationName("Hotel Chain").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("localRealm");
    }

    @Test
    void federationName_required() {
        assertThatThrownBy(() ->
                        FederationConfig.builder().localRealm("hotel-downtown").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("federationName");
    }

    @Test
    void realms_areImmutableCopy() {
        FederationConfig config = FederationConfig.builder()
                .localRealm("hotel-downtown")
                .federationName("Hotel Chain")
                .realm("hotel-airport", "hotel-airport-ns")
                .build();

        assertThat(config.realms()).isUnmodifiable();
    }

    @Test
    void emptyRealmsMap_isOk() {
        FederationConfig config = FederationConfig.builder()
                .localRealm("hotel-downtown")
                .federationName("Hotel Chain")
                .build();

        assertThat(config.realms()).isEmpty();
    }

    @Test
    void recordConstructor_nullRealmsDefaultsToEmpty() {
        FederationConfig config = new FederationConfig("local", "federation", null);

        assertThat(config.realms()).isEmpty();
    }

    @Test
    void recordConstructor_nullLocalRealmThrows() {
        assertThatThrownBy(() -> new FederationConfig(null, "federation", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("localRealm");
    }

    @Test
    void recordConstructor_nullFederationNameThrows() {
        assertThatThrownBy(() -> new FederationConfig("local", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("federationName");
    }
}
