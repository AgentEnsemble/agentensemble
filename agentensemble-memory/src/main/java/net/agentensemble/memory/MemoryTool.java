package net.agentensemble.memory;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A built-in tool that gives agents explicit mid-task access to a named memory scope.
 *
 * <p>Agents can call this tool during their ReAct loop to store arbitrary key-value pairs
 * and retrieve previously stored entries by semantic similarity (when backed by an
 * {@link MemoryStore#embeddings} store) or by recency (when backed by
 * {@link MemoryStore#inMemory()}).
 *
 * <p>Create an instance with {@link #of(String, MemoryStore)} and add it to the agent's
 * tool list:
 * <pre>
 * MemoryStore store = MemoryStore.inMemory();
 *
 * Agent researcher = Agent.builder()
 *     .role("Researcher")
 *     .goal("Research and remember facts")
 *     .tools(MemoryTool.of("research", store))
 *     .llm(llm)
 *     .build();
 * </pre>
 *
 * <p>When the same {@code MemoryStore} instance is shared between the tool and
 * {@code Ensemble.builder().memoryStore(store)}, automatic scope-based storage and
 * explicit tool access are unified in the same backing store.
 */
public class MemoryTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private static final int DEFAULT_RETRIEVE_MAX = 5;

    private final String scope;
    private final MemoryStore memoryStore;

    private MemoryTool(String scope, MemoryStore memoryStore) {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("MemoryTool scope must not be null or blank");
        }
        if (memoryStore == null) {
            throw new IllegalArgumentException("MemoryTool memoryStore must not be null");
        }
        this.scope = scope;
        this.memoryStore = memoryStore;
    }

    /**
     * Create a new {@code MemoryTool} for the given scope and store.
     *
     * @param scope       the memory scope to read from and write to; must not be blank
     * @param memoryStore the backing store; must not be null
     * @return a new MemoryTool
     */
    public static MemoryTool of(String scope, MemoryStore memoryStore) {
        return new MemoryTool(scope, memoryStore);
    }

    /**
     * Store a fact in memory so it can be retrieved in this or future runs.
     *
     * @param key   a short label or key for the fact
     * @param value the fact or information to remember
     * @return a confirmation message
     */
    @Tool("Store a fact or piece of information in memory for future reference")
    public String storeMemory(
            @P("A short key or label for the information") String key,
            @P("The information or fact to remember") String value) {
        if (key == null || key.isBlank()) {
            return "Error: key must not be blank";
        }
        if (value == null || value.isBlank()) {
            return "Error: value must not be blank";
        }
        String content = key + ": " + value;
        MemoryEntry entry = MemoryEntry.builder()
                .content(content)
                .storedAt(Instant.now())
                .metadata(Map.of("key", key, "source", "MemoryTool"))
                .build();
        memoryStore.store(scope, entry);
        log.debug("MemoryTool stored entry | scope: '{}' | key: '{}'", scope, key);
        return "Stored: " + key;
    }

    /**
     * Retrieve relevant information from memory using the given query.
     *
     * @param query the query to search memory with
     * @return a formatted string of matching memory entries, or a not-found message
     */
    @Tool("Retrieve relevant information from memory using a search query")
    public String retrieveMemory(@P("The search query to find relevant memories") String query) {
        if (query == null || query.isBlank()) {
            return "No query provided";
        }
        List<MemoryEntry> entries = memoryStore.retrieve(scope, query, DEFAULT_RETRIEVE_MAX);
        if (entries.isEmpty()) {
            return "No relevant memories found for: " + query;
        }
        StringBuilder sb = new StringBuilder("Retrieved memories:\n");
        for (MemoryEntry entry : entries) {
            sb.append("- ").append(entry.getContent()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Return the scope this tool reads from and writes to.
     *
     * @return the scope name
     */
    public String getScope() {
        return scope;
    }
}
