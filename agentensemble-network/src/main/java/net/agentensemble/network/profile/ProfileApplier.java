package net.agentensemble.network.profile;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.network.memory.SharedMemory;
import net.agentensemble.network.memory.SharedMemoryRegistry;
import net.agentensemble.web.protocol.CapacitySpec;
import net.agentensemble.web.protocol.ProfileAppliedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies an operational profile: executes pre-load directives and broadcasts the profile change.
 */
public class ProfileApplier {

    private static final Logger log = LoggerFactory.getLogger(ProfileApplier.class);

    private final SharedMemoryRegistry sharedMemoryRegistry;
    private final Consumer<ProfileAppliedMessage> broadcaster;

    public ProfileApplier(SharedMemoryRegistry sharedMemoryRegistry, Consumer<ProfileAppliedMessage> broadcaster) {
        this.sharedMemoryRegistry = Objects.requireNonNull(sharedMemoryRegistry);
        this.broadcaster = Objects.requireNonNull(broadcaster);
    }

    /**
     * Apply a profile: execute pre-load directives, then broadcast the profile change.
     */
    public void apply(NetworkProfile profile) {
        Objects.requireNonNull(profile, "profile must not be null");
        log.info("Applying operational profile '{}'", profile.name());

        // 1. Execute pre-load directives
        for (PreloadDirective directive : profile.preloadDirectives()) {
            if (sharedMemoryRegistry.contains(directive.memoryScope())) {
                SharedMemory sm = sharedMemoryRegistry.get(directive.memoryScope());
                MemoryEntry entry = MemoryEntry.builder()
                        .content(directive.content())
                        .storedAt(Instant.now())
                        .build();
                sm.store(directive.memoryScope(), entry);
                log.debug(
                        "Pre-loaded '{}' into scope '{}' for ensemble '{}'",
                        directive.content(),
                        directive.memoryScope(),
                        directive.ensembleName());
            } else {
                log.warn(
                        "Shared memory scope '{}' not registered; skipping preload for ensemble '{}'",
                        directive.memoryScope(),
                        directive.ensembleName());
            }
        }

        // 2. Build and broadcast ProfileAppliedMessage
        Map<String, CapacitySpec> capacitySpecs = new HashMap<>();
        for (Map.Entry<String, Capacity> entry : profile.ensembleCapacities().entrySet()) {
            Capacity c = entry.getValue();
            capacitySpecs.put(entry.getKey(), new CapacitySpec(c.replicas(), c.maxConcurrent(), c.dormant()));
        }

        ProfileAppliedMessage message = new ProfileAppliedMessage(
                profile.name(), capacitySpecs, Instant.now().toString());
        broadcaster.accept(message);
        log.info("Profile '{}' applied and broadcast", profile.name());
    }
}
