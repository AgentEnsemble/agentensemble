package net.agentensemble.network.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkProfile} builder.
 */
class NetworkProfileBuilderTest {

    @Test
    void nameRequired() {
        assertThatThrownBy(() -> NetworkProfile.builder().build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankNameThrows() {
        assertThatThrownBy(() -> NetworkProfile.builder().name("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");
    }

    @Test
    void emptyNameThrows() {
        assertThatThrownBy(() -> NetworkProfile.builder().name("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name must not be blank");
    }

    @Test
    void builderAccumulatesEnsemblesAndPreloads() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("sporting-event-weekend")
                .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
                .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
                .preload("kitchen", "inventory", "Extra beer and ice stocked")
                .preload("front-desk", "events", "Sports bar at full capacity tonight")
                .build();

        assertThat(profile.name()).isEqualTo("sporting-event-weekend");
        assertThat(profile.ensembleCapacities()).hasSize(2);
        assertThat(profile.ensembleCapacities().get("front-desk").replicas()).isEqualTo(4);
        assertThat(profile.ensembleCapacities().get("kitchen").maxConcurrent()).isEqualTo(100);
        assertThat(profile.preloadDirectives()).hasSize(2);
        assertThat(profile.preloadDirectives().get(0).ensembleName()).isEqualTo("kitchen");
        assertThat(profile.preloadDirectives().get(0).memoryScope()).isEqualTo("inventory");
        assertThat(profile.preloadDirectives().get(0).content()).isEqualTo("Extra beer and ice stocked");
    }

    @Test
    void collectionsAreImmutable() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("test")
                .ensemble("a", Capacity.replicas(1).maxConcurrent(10))
                .preload("a", "scope", "content")
                .build();

        assertThatThrownBy(() -> profile.ensembleCapacities()
                        .put("b", Capacity.replicas(2).maxConcurrent(20)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> profile.preloadDirectives().add(new PreloadDirective("b", "s", "c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyProfileJustNameIsValid() {
        NetworkProfile profile = NetworkProfile.builder().name("minimal").build();

        assertThat(profile.name()).isEqualTo("minimal");
        assertThat(profile.ensembleCapacities()).isEmpty();
        assertThat(profile.preloadDirectives()).isEmpty();
    }

    @Test
    void nullEnsembleNameThrows() {
        assertThatThrownBy(() -> NetworkProfile.builder()
                        .name("test")
                        .ensemble(null, Capacity.replicas(1).maxConcurrent(10)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensemble name");
    }

    @Test
    void nullCapacityThrows() {
        assertThatThrownBy(() -> NetworkProfile.builder().name("test").ensemble("a", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void duplicateEnsembleNameOverwrites() {
        NetworkProfile profile = NetworkProfile.builder()
                .name("test")
                .ensemble("kitchen", Capacity.replicas(2).maxConcurrent(20))
                .ensemble("kitchen", Capacity.replicas(4).maxConcurrent(50))
                .build();

        assertThat(profile.ensembleCapacities()).hasSize(1);
        assertThat(profile.ensembleCapacities().get("kitchen").replicas()).isEqualTo(4);
    }
}
