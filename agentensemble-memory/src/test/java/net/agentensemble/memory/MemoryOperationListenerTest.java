package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryOperationListenerTest {

    /**
     * The four default methods must be callable without throwing.
     * This test covers the default method bodies which otherwise have 0% JaCoCo coverage.
     */
    @Test
    void testDefaultMethods_noOpImplementation_doesNotThrow() {
        // Anonymous class with no overrides -- all calls delegate to the default bodies
        MemoryOperationListener listener = new MemoryOperationListener() {};

        assertThatCode(() -> {
                    listener.onStmWrite();
                    listener.onLtmStore();
                    listener.onLtmRetrieval(Duration.ofMillis(50));
                    listener.onEntityLookup(Duration.ofMillis(25));
                })
                .doesNotThrowAnyException();
    }

    @Test
    void testDefaultMethods_nullDuration_doesNotThrow() {
        MemoryOperationListener listener = new MemoryOperationListener() {};

        assertThatCode(() -> {
                    listener.onLtmRetrieval(null);
                    listener.onEntityLookup(null);
                })
                .doesNotThrowAnyException();
    }
}
