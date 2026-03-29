package net.agentensemble.network.profile;

import java.util.Objects;

/**
 * A directive to pre-load content into shared memory when a profile is applied.
 *
 * @param ensembleName the target ensemble
 * @param memoryScope  the shared memory scope name
 * @param content      the content to store
 */
public record PreloadDirective(String ensembleName, String memoryScope, String content) {

    public PreloadDirective {
        Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        Objects.requireNonNull(memoryScope, "memoryScope must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
