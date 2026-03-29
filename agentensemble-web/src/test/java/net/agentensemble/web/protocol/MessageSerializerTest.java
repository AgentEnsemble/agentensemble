package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MessageSerializer}: toJson, fromJson, and toJsonNode.
 */
class MessageSerializerTest {

    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
    }

    // ========================
    // toJsonNode
    // ========================

    @Test
    void toJsonNode_nullInput_returnsNull() {
        assertThat(serializer.toJsonNode(null)).isNull();
    }

    @Test
    void toJsonNode_blankInput_returnsNull() {
        assertThat(serializer.toJsonNode("")).isNull();
        assertThat(serializer.toJsonNode("   ")).isNull();
    }

    @Test
    void toJsonNode_invalidJson_returnsNull() {
        assertThat(serializer.toJsonNode("not-valid-json")).isNull();
    }

    @Test
    void toJsonNode_validJson_returnsNode() {
        JsonNode node = serializer.toJsonNode("{\"key\":\"value\"}");
        assertThat(node).isNotNull();
        assertThat(node.get("key").asText()).isEqualTo("value");
    }

    @Test
    void toJsonNode_jsonArray_returnsNode() {
        JsonNode node = serializer.toJsonNode("[1,2,3]");
        assertThat(node).isNotNull();
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isEqualTo(3);
    }

    // ========================
    // toJson error handling
    // ========================

    @Test
    void toJson_unserializableObject_throwsIllegalStateException() {
        // An object that causes Jackson serialization to fail
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                return this; // Circular reference
            }
        };
        assertThatThrownBy(() -> serializer.toJson(unserializable))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to serialize");
    }
}
