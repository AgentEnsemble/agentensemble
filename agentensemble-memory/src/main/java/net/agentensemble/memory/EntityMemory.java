package net.agentensemble.memory;

import java.util.Map;
import java.util.Optional;

/**
 * Strategy interface for tracking known facts about named entities.
 *
 * Entity memory provides a key-value store where the key is an entity name
 * (a person, company, concept, etc.) and the value is a known fact about
 * that entity. All stored facts are injected into each agent's prompt so
 * agents can reason with consistent knowledge across the ensemble.
 *
 * Entity memory persists for as long as the implementation instance is
 * referenced. Users seed the store with known facts before running the
 * ensemble, and the framework injects those facts into agent prompts.
 *
 * The built-in implementation is {@link InMemoryEntityMemory}.
 *
 * Example:
 * <pre>
 * EntityMemory entities = new InMemoryEntityMemory();
 * entities.put("Acme Corp", "A mid-sized software company founded in 2010, publicly traded as ACME");
 * entities.put("Alice", "The lead researcher on this project, specialising in NLP");
 *
 * EnsembleMemory memory = EnsembleMemory.builder()
 *     .entityMemory(entities)
 *     .build();
 * </pre>
 */
public interface EntityMemory {

    /**
     * Store a fact about a named entity.
     *
     * If a fact for this entity already exists, it is replaced.
     *
     * @param entityName the name of the entity; must not be null or blank
     * @param fact       the fact to associate with the entity; must not be null
     */
    void put(String entityName, String fact);

    /**
     * Retrieve the stored fact for a named entity, if present.
     *
     * @param entityName the name of the entity
     * @return an Optional containing the fact, or empty if not found
     */
    Optional<String> get(String entityName);

    /**
     * Return all stored entity-fact pairs.
     *
     * @return unmodifiable map of entity names to facts; never null
     */
    Map<String, String> getAll();

    /**
     * Return true if no entity facts have been stored.
     *
     * @return true if empty
     */
    boolean isEmpty();
}
