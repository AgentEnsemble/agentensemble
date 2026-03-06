package net.agentensemble.web.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Serializes and deserializes wire protocol messages to and from JSON.
 *
 * <p>The configured {@link ObjectMapper} is shared across all serializer instances.
 * Jackson's {@code @JsonTypeInfo} + {@code @JsonSubTypes} on {@link ServerMessage} and
 * {@link ClientMessage} drive polymorphic serialization using the {@code type} property.
 *
 * <p>Thread-safe: {@link ObjectMapper} is thread-safe after configuration.
 */
public class MessageSerializer {

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Serialize a {@link ServerMessage} (or any protocol message) to a JSON string.
     *
     * @param message the message to serialize; must not be null
     * @return JSON representation with {@code type} discriminator field
     * @throws IllegalStateException if serialization fails (unexpected; indicates a bug)
     */
    public String toJson(Object message) {
        try {
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize message: " + message.getClass().getSimpleName(), e);
        }
    }

    /**
     * Parses a raw JSON string into a {@link JsonNode} for use in snapshot fields.
     * Returns {@code null} if {@code json} is {@code null}, blank, or not valid JSON.
     *
     * @param json the JSON string to parse; may be null
     * @return the parsed {@link JsonNode}, or {@code null} on failure
     */
    public JsonNode toJsonNode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Deserialize a JSON string to the given message type.
     *
     * <p>For {@link ServerMessage} and {@link ClientMessage}, Jackson uses the {@code type}
     * property to select the correct subtype.
     *
     * @param json  the JSON string to deserialize; must not be null
     * @param clazz the target type ({@link ServerMessage}, {@link ClientMessage}, or a concrete subtype)
     * @param <T>   the target type
     * @return the deserialized message
     * @throws IllegalArgumentException if the JSON is malformed or the type is unknown
     */
    public <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize message from JSON: " + json, e);
        }
    }
}
