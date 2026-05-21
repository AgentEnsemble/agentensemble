package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;

/**
 * Identity and metadata for a single producer process publishing live events into a
 * {@code LiveEventHub}.
 *
 * <p>The hub keys all per-producer state by {@link #producerId()}; restarting a process with the
 * same {@code producerId} re-attaches to the same hub state (snapshot retained). All other fields
 * are optional metadata used for filtering and display: {@code serviceName} groups horizontally
 * scaled replicas, {@code instanceId} disambiguates replicas, {@code host} records the process's
 * hostname, {@code version} the application version, and {@code tags} carry application-specific
 * labels (environment, region, etc.).
 *
 * <p>Wire-safe: all metadata fields are {@code @JsonInclude(NON_NULL)} so older clients that do
 * not know about a field simply receive null and ignore it. {@code @JsonIgnoreProperties} lets
 * forward-compatible fields ride along without breaking deserialization.
 *
 * @param producerId  unique identifier for this producer; required; must remain stable across
 *                    restarts to keep the hub's snapshot continuity
 * @param serviceName logical service name (e.g. {@code "kitchen"}); groups replicas
 * @param instanceId  per-process instance identifier (e.g. K8s pod name); may be null
 * @param host        hostname or pod IP; may be null
 * @param version     application version string; may be null
 * @param tags        free-form labels (e.g. {@code {"env":"prod","region":"us-east"}}); may be null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProducerInfo(
        String producerId,
        String serviceName,
        String instanceId,
        String host,
        String version,
        Map<String, String> tags) {

    public ProducerInfo {
        Objects.requireNonNull(producerId, "producerId must not be null");
        if (producerId.isBlank()) {
            throw new IllegalArgumentException("producerId must not be blank");
        }
    }

    /**
     * Convenience factory for the common minimum case: identity only.
     *
     * @param producerId  unique producer identifier; required
     * @param serviceName logical service name; required for the common deployment shape
     * @return a {@code ProducerInfo} with only identity fields set
     */
    public static ProducerInfo of(String producerId, String serviceName) {
        return new ProducerInfo(producerId, serviceName, null, null, null, null);
    }

    /**
     * Convenience factory matching a typical K8s deployment shape.
     *
     * @param producerId  unique producer identifier
     * @param serviceName logical service name (e.g. K8s service name)
     * @param instanceId  per-process instance identifier (e.g. K8s pod name)
     * @param host        hostname or pod IP
     * @return a {@code ProducerInfo} with K8s-style metadata
     */
    public static ProducerInfo of(String producerId, String serviceName, String instanceId, String host) {
        return new ProducerInfo(producerId, serviceName, instanceId, host, null, null);
    }
}
