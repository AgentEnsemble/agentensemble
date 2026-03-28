package net.agentensemble.transport.redis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.UncheckedIOException;

/**
 * Internal JSON codec for serializing {@link net.agentensemble.web.protocol.WorkRequest}
 * and {@link net.agentensemble.web.protocol.WorkResponse} to/from Redis.
 *
 * <p>Thread-safe: the underlying {@link ObjectMapper} is configured at construction and
 * never modified.
 */
final class RedisCodec {

    private final ObjectMapper mapper;

    RedisCodec() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Serialize an object to a JSON string.
     *
     * @param value the object to serialize; must not be null
     * @return the JSON string representation
     * @throws UncheckedIOException if serialization fails
     */
    String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }

    /**
     * Deserialize a JSON string to an object of the given type.
     *
     * @param json the JSON string; must not be null
     * @param type the target type; must not be null
     * @param <T>  the target type
     * @return the deserialized object
     * @throws UncheckedIOException if deserialization fails
     */
    <T> T deserialize(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }
}
