package net.agentensemble.network.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Capacity} and its fluent builder.
 */
class CapacityTest {

    @Test
    void builderChain_replicasAndMaxConcurrent() {
        Capacity capacity = Capacity.replicas(4).maxConcurrent(50);

        assertThat(capacity.replicas()).isEqualTo(4);
        assertThat(capacity.maxConcurrent()).isEqualTo(50);
        assertThat(capacity.dormant()).isFalse();
    }

    @Test
    void builderChain_withDormant() {
        Capacity capacity = Capacity.replicas(0).dormant(true).build();

        assertThat(capacity.replicas()).isZero();
        assertThat(capacity.dormant()).isTrue();
    }

    @Test
    void builderChain_dormantAndMaxConcurrent() {
        Capacity capacity = Capacity.replicas(2).dormant(true).maxConcurrent(30);

        assertThat(capacity.replicas()).isEqualTo(2);
        assertThat(capacity.maxConcurrent()).isEqualTo(30);
        assertThat(capacity.dormant()).isTrue();
    }

    @Test
    void builderDefaultMaxConcurrent() {
        Capacity capacity = Capacity.replicas(3).build();

        assertThat(capacity.replicas()).isEqualTo(3);
        assertThat(capacity.maxConcurrent()).isEqualTo(10); // default
        assertThat(capacity.dormant()).isFalse();
    }

    @Test
    void directConstruction() {
        Capacity capacity = new Capacity(5, 100, true);

        assertThat(capacity.replicas()).isEqualTo(5);
        assertThat(capacity.maxConcurrent()).isEqualTo(100);
        assertThat(capacity.dormant()).isTrue();
    }

    @Test
    void negativeReplicasThrows() {
        assertThatThrownBy(() -> new Capacity(-1, 10, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicas");
    }

    @Test
    void negativeReplicasViaBuilderThrows() {
        assertThatThrownBy(() -> Capacity.replicas(-1).maxConcurrent(10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicas");
    }

    @Test
    void negativeMaxConcurrentThrows() {
        assertThatThrownBy(() -> new Capacity(1, -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrent");
    }

    @Test
    void negativeMaxConcurrentViaBuilderThrows() {
        assertThatThrownBy(() -> Capacity.replicas(1).maxConcurrent(-5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrent");
    }

    @Test
    void zeroReplicasIsValid() {
        Capacity capacity = Capacity.replicas(0).maxConcurrent(0);
        assertThat(capacity.replicas()).isZero();
        assertThat(capacity.maxConcurrent()).isZero();
    }
}
