package net.agentensemble.format;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON implementation of {@link ContextFormatter}.
 *
 * <p>Always available -- uses the Jackson {@link ObjectMapper} that is already
 * on the classpath as a transitive dependency of {@code agentensemble-core}.
 *
 * <p>{@link #format(Object)} serializes Java objects to compact JSON.
 * {@link #formatJson(String)} is a no-op that returns the input unchanged
 * (the data is already JSON).
 */
final class JsonContextFormatter implements ContextFormatter {

    private static final ObjectMapper MAPPER = buildMapper();

    @Override
    public String format(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Fall back to toString() for non-serializable objects
            return value.toString();
        }
    }

    @Override
    public String formatJson(String json) {
        if (json == null) {
            return "null";
        }
        return json;
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        return mapper;
    }
}
