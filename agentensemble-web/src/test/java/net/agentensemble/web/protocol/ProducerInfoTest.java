package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProducerInfoTest {

    @Test
    void factory_identityOnly_setsServiceName() {
        ProducerInfo info = ProducerInfo.of("p1", "svc");
        assertThat(info.producerId()).isEqualTo("p1");
        assertThat(info.serviceName()).isEqualTo("svc");
        assertThat(info.instanceId()).isNull();
        assertThat(info.host()).isNull();
        assertThat(info.version()).isNull();
        assertThat(info.tags()).isNull();
    }

    @Test
    void factory_fullK8sShape_populatesAllMetadata() {
        ProducerInfo info = ProducerInfo.of("p1", "svc", "pod-1", "node-12");
        assertThat(info.instanceId()).isEqualTo("pod-1");
        assertThat(info.host()).isEqualTo("node-12");
    }

    @Test
    void constructor_acceptsTagsAndVersion() {
        ProducerInfo info = new ProducerInfo("p1", "svc", "i1", "h1", "v1.0", Map.of("env", "prod"));
        assertThat(info.version()).isEqualTo("v1.0");
        assertThat(info.tags()).containsEntry("env", "prod");
    }

    @Test
    void nullProducerId_rejected() {
        assertThatThrownBy(() -> new ProducerInfo(null, "svc", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void blankProducerId_rejected() {
        assertThatThrownBy(() -> new ProducerInfo("   ", "svc", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }
}
