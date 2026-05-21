package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Sent by a {@code LiveEventHub} to a browser client immediately upon connection. The
 * multi-producer counterpart to {@link HelloMessage}: distinct discriminator
 * ({@code hub_hello}) so embedded-mode browsers and hub-mode browsers can each pick the
 * right reducer.
 *
 * <p>Carries the union of currently retained producer identities, a flattened
 * {@link #snapshotTrace} of all enveloped events across all retained producers (chronological by
 * {@link LiveEventEnvelope#receivedAt()}), and per-producer recent LLM iteration snapshots so the
 * browser can hydrate the conversation panel for any producer without waiting for the next live
 * event.
 *
 * @param producers              all producers known to the hub at connect time
 * @param snapshotTrace          flattened JSON array of {@link LiveEventEnvelope}s; null when empty
 * @param iterationsByProducer   recent iteration snapshots keyed by {@code ProducerInfo.producerId}; null when empty
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HubHelloMessage(
        List<ProducerInfo> producers, JsonNode snapshotTrace, Map<String, List<IterationSnapshot>> iterationsByProducer)
        implements ServerMessage {}
