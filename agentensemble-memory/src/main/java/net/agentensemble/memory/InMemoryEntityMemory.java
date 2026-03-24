package net.agentensemble.memory;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory implementation of {@link EntityMemory}.
 *
 * Stores entity facts in a {@link ConcurrentHashMap}. Facts are not persisted
 * across JVM restarts. Suitable for single-run use or testing.
 *
 * Example:
 * <pre>
 * InMemoryEntityMemory entities = new InMemoryEntityMemory();
 * entities.put("OpenAI", "A US AI research lab founded in 2015");
 * entities.put("LangChain4j", "A Java library for building LLM-powered applications");
 * </pre>
 */
public class InMemoryEntityMemory implements EntityMemory {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public void put(String entityName, String fact) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("entityName must not be null or blank");
        }
        if (fact == null) {
            throw new IllegalArgumentException("fact must not be null");
        }
        store.put(entityName.trim(), fact);
    }

    @Override
    public Optional<String> get(String entityName) {
        if (entityName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(entityName.trim()));
    }

    @Override
    public Map<String, String> getAll() {
        return Collections.unmodifiableMap(store);
    }

    @Override
    public boolean isEmpty() {
        return store.isEmpty();
    }
}
