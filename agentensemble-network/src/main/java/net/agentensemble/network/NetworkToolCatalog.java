package net.agentensemble.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.DynamicToolProvider;
import net.agentensemble.web.protocol.SharedCapabilityInfo;

/**
 * A {@link DynamicToolProvider} that resolves network tools at task execution time.
 *
 * <p>Place into {@code Task.builder().tools()} to make all (or tagged) network tools
 * available to an agent. Tools are resolved fresh on each execution, so new ensembles
 * that come online are immediately discoverable.
 *
 * <pre>
 * Task.builder()
 *     .tools(NetworkToolCatalog.all(registry))          // all tools
 *     .tools(NetworkToolCatalog.tagged("food", registry)) // filtered
 *     .build();
 * </pre>
 *
 * @see DynamicToolProvider
 * @see NetworkTool
 */
public final class NetworkToolCatalog implements DynamicToolProvider {

    private final NetworkClientRegistry clientRegistry;
    private final String tagFilter;

    private NetworkToolCatalog(NetworkClientRegistry clientRegistry, String tagFilter) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.tagFilter = tagFilter;
    }

    /**
     * Create a catalog that resolves all TOOL capabilities on the network.
     *
     * @param clientRegistry the client registry with capability information; must not be null
     * @return a new catalog that resolves all tools
     */
    public static NetworkToolCatalog all(NetworkClientRegistry clientRegistry) {
        return new NetworkToolCatalog(clientRegistry, null);
    }

    /**
     * Create a catalog filtered by tag.
     *
     * @param tag            the tag to filter capabilities by; must not be null
     * @param clientRegistry the client registry with capability information; must not be null
     * @return a new catalog that resolves tools matching the tag
     */
    public static NetworkToolCatalog tagged(String tag, NetworkClientRegistry clientRegistry) {
        Objects.requireNonNull(tag, "tag must not be null");
        return new NetworkToolCatalog(clientRegistry, tag);
    }

    @Override
    public List<AgentTool> resolve() {
        CapabilityRegistry registry = clientRegistry.getCapabilityRegistry();
        Map<String, List<SharedCapabilityInfo>> byEnsemble =
                tagFilter != null ? registry.findByTagWithEnsemble(tagFilter) : registry.allByEnsemble();
        List<AgentTool> tools = new ArrayList<>();
        for (Map.Entry<String, List<SharedCapabilityInfo>> entry : byEnsemble.entrySet()) {
            String ensemble = entry.getKey();
            for (SharedCapabilityInfo cap : entry.getValue()) {
                if ("TOOL".equals(cap.type())) {
                    tools.add(NetworkTool.from(ensemble, cap.name(), clientRegistry));
                }
            }
        }
        return tools;
    }

    /**
     * Returns the tag filter, or {@code null} if this catalog returns all tools.
     *
     * @return the tag filter, or null
     */
    public String tagFilter() {
        return tagFilter;
    }
}
