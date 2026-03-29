package net.agentensemble.network.memory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of named {@link SharedMemory} instances at the network level.
 */
public class SharedMemoryRegistry {

    private final ConcurrentHashMap<String, SharedMemory> memories = new ConcurrentHashMap<>();

    public void register(String name, SharedMemory sharedMemory) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(sharedMemory, "sharedMemory must not be null");
        if (memories.putIfAbsent(name, sharedMemory) != null) {
            throw new IllegalArgumentException("Shared memory '" + name + "' is already registered");
        }
    }

    public SharedMemory get(String name) {
        Objects.requireNonNull(name, "name must not be null");
        SharedMemory sm = memories.get(name);
        if (sm == null) {
            throw new IllegalArgumentException("No shared memory registered with name '" + name + "'");
        }
        return sm;
    }

    public boolean contains(String name) {
        return memories.containsKey(name);
    }

    public Set<String> names() {
        return Set.copyOf(memories.keySet());
    }
}
