package net.agentensemble.reflection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import net.agentensemble.Task;

/**
 * Derives a stable, content-based identity key for a task, for use as a
 * {@link ReflectionStore} lookup key.
 *
 * <p>The key is a hex-encoded SHA-256 hash of the task's description string, computed on
 * UTF-8 bytes. This provides:
 * <ul>
 *   <li>Stability across JVM restarts</li>
 *   <li>Consistency regardless of other task fields</li>
 *   <li>Natural semantic grouping — tasks with the same description share an identity</li>
 * </ul>
 *
 * <p>Changing a task's description creates a new identity and therefore a new reflection
 * entry — a description change represents a new task definition.
 *
 * <p>Custom {@link ReflectionStore} implementations may use this utility to compute the
 * same stable key the framework uses internally.
 */
public final class TaskIdentity {

    private TaskIdentity() {}

    /**
     * Derives the identity key for the given task.
     *
     * @param task the task to identify; must not be null
     * @return hex-encoded SHA-256 hash of the task's description
     */
    public static String of(Task task) {
        return sha256Hex(task.getDescription());
    }

    /**
     * Computes the hex-encoded SHA-256 hash of the given string.
     *
     * @param input the string to hash; must not be null
     * @return lowercase hex-encoded SHA-256 digest
     */
    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec (java.security.MessageDigest)
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
