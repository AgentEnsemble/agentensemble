package net.agentensemble.reflection;

import java.util.Optional;

/**
 * SPI for persisting and retrieving task reflections across separate
 * {@code Ensemble.run()} invocations.
 *
 * <p>A {@code ReflectionStore} holds one {@link TaskReflection} per task identity.
 * Each successful reflection execution replaces the prior entry (last-write-wins).
 *
 * <p>Implementations may use any storage backend: in-process memory, RDBMS, SQLite,
 * Redis, REST API, or any other durable store. The framework ships
 * {@link InMemoryReflectionStore} as the default; it is suitable for development,
 * testing, and single-JVM deployments. For persistence across JVM restarts, provide
 * a custom implementation backed by durable storage.
 *
 * <h2>Registration</h2>
 * Configure a store on the {@code Ensemble} builder:
 * <pre>
 * Ensemble.builder()
 *     .reflectionStore(new MyDbReflectionStore(dataSource))
 *     .build();
 * </pre>
 *
 * <h2>Task Identity</h2>
 * The framework derives a stable task identity from the task's description using a
 * SHA-256 hash. This means:
 * <ul>
 *   <li>Identity is stable across JVM restarts</li>
 *   <li>Two tasks with the same description share a reflection entry</li>
 *   <li>Changing a task's description creates a new reflection entry</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * Implementations must be safe for concurrent invocations from parallel workflow
 * virtual threads. {@link InMemoryReflectionStore} uses a {@code ConcurrentHashMap}
 * and is safe by default.
 */
public interface ReflectionStore {

    /**
     * Store or replace the reflection for the given task identity.
     *
     * <p>If a reflection already exists for {@code taskIdentity}, it is replaced.
     * This is always correct — a newer reflection supersedes the prior one.
     *
     * @param taskIdentity hex-encoded SHA-256 hash of the task description; must not be null or blank
     * @param reflection   the reflection to store; must not be null
     * @throws NullPointerException     if either argument is null
     * @throws IllegalArgumentException if {@code taskIdentity} is blank
     */
    void store(String taskIdentity, TaskReflection reflection);

    /**
     * Retrieve the stored reflection for the given task identity.
     *
     * @param taskIdentity hex-encoded SHA-256 hash of the task description; must not be null or blank
     * @return the stored reflection wrapped in an {@link Optional}, or {@link Optional#empty()}
     *         if no reflection exists for this identity
     * @throws NullPointerException     if {@code taskIdentity} is null
     * @throws IllegalArgumentException if {@code taskIdentity} is blank
     */
    Optional<TaskReflection> retrieve(String taskIdentity);
}
