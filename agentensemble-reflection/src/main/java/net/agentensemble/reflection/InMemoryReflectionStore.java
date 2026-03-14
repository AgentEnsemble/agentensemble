package net.agentensemble.reflection;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-process {@link ReflectionStore} implementation backed by a
 * {@code ConcurrentHashMap}.
 *
 * <p>This is the default store used when a task has reflection enabled but no
 * {@code ReflectionStore} is configured on the {@code Ensemble}. It is suitable for
 * development, testing, and single-JVM deployments.
 *
 * <p><strong>Persistence:</strong> Reflections stored here do not survive JVM restarts.
 * To simulate cross-run persistence in tests, reuse the same {@code InMemoryReflectionStore}
 * instance across multiple {@code Ensemble.run()} invocations. For production use cases
 * requiring durable persistence, implement {@link ReflectionStore} with a database or
 * file-based backend.
 *
 * <p><strong>Thread safety:</strong> All operations are safe for concurrent use from
 * parallel workflow virtual threads.
 *
 * <h2>Usage</h2>
 * <pre>
 * ReflectionStore store = new InMemoryReflectionStore();
 *
 * Ensemble ensemble = Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .reflectionStore(store)
 *     .build();
 *
 * // Run multiple times -- store accumulates reflections
 * ensemble.run(reflectingTask);
 * ensemble.run(reflectingTask);  // prompt now includes prior reflection
 * </pre>
 */
public final class InMemoryReflectionStore implements ReflectionStore {

    private final Map<String, TaskReflection> entries = new ConcurrentHashMap<>();

    /**
     * Store or replace the reflection for the given task identity.
     *
     * <p>Thread-safe: uses {@code ConcurrentHashMap.put()} which is appropriate because
     * a later reflection always supersedes the prior one.
     *
     * @param taskIdentity the task identity key; must not be null or blank
     * @param reflection   the reflection to store; must not be null
     */
    @Override
    public void store(String taskIdentity, TaskReflection reflection) {
        Objects.requireNonNull(taskIdentity, "taskIdentity must not be null");
        Objects.requireNonNull(reflection, "reflection must not be null");
        if (taskIdentity.isBlank()) {
            throw new IllegalArgumentException("taskIdentity must not be blank");
        }
        entries.put(taskIdentity, reflection);
    }

    /**
     * Retrieve the stored reflection for the given task identity.
     *
     * @param taskIdentity the task identity key; must not be null or blank
     * @return the stored reflection, or {@link Optional#empty()} if none exists
     */
    @Override
    public Optional<TaskReflection> retrieve(String taskIdentity) {
        Objects.requireNonNull(taskIdentity, "taskIdentity must not be null");
        if (taskIdentity.isBlank()) {
            throw new IllegalArgumentException("taskIdentity must not be blank");
        }
        return Optional.ofNullable(entries.get(taskIdentity));
    }

    /**
     * Returns the number of task identities with stored reflections.
     *
     * @return the count of stored reflections
     */
    public int size() {
        return entries.size();
    }

    /**
     * Removes all stored reflections from this store.
     *
     * <p>Primarily useful in tests to reset state between test cases when reusing
     * the same store instance.
     */
    public void clear() {
        entries.clear();
    }
}
