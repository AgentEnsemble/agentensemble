package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

/**
 * Wire-protocol representation of a shared capability for the capability handshake.
 *
 * <p>Sent as part of the {@link HelloMessage} when a client connects to an
 * ensemble that has shared tasks or tools. This is the serialized form of
 * {@link net.agentensemble.ensemble.SharedCapability} optimized for the wire
 * protocol.
 *
 * @param name        unique capability name within the ensemble
 * @param description human-readable description
 * @param type        either {@code "TASK"} or {@code "TOOL"}
 * @param tags        optional classification tags for discovery filtering
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SharedCapabilityInfo(String name, String description, String type, List<String> tags) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if name or type is null
     */
    public SharedCapabilityInfo {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (description == null) {
            description = "";
        }
        if (tags == null) {
            tags = List.of();
        }
    }

    /**
     * Backward-compatible constructor without tags.
     */
    public SharedCapabilityInfo(String name, String description, String type) {
        this(name, description, type, null);
    }
}
