package net.agentensemble.examples;

import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.MaxLoopIterationsExceededException;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.workflow.loop.Loop;
import net.agentensemble.workflow.loop.LoopMemoryMode;
import net.agentensemble.workflow.loop.MaxIterationsAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Demonstrates the {@link Loop} construct in a retry-until-valid pattern, contrasting with
 * the reflection-loop example:
 *
 * <ul>
 *   <li>{@code MaxIterationsAction.THROW} -- non-convergence is a hard error rather than a
 *       degraded output.</li>
 *   <li>{@code LoopMemoryMode.FRESH_PER_ITERATION} -- prior bad outputs do not leak into the
 *       next iteration's prompt. Requires {@code MemoryStore.inMemory()} (or another store
 *       that supports {@code clear(scope)}).</li>
 * </ul>
 *
 * <p>The example shows two scenarios:
 * <ol>
 *   <li>The validator passes on iteration 3; the loop terminates via the predicate.</li>
 *   <li>The validator never passes; the loop hits its cap and {@link MaxLoopIterationsExceededException}
 *       is thrown.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 * ./gradlew :agentensemble-examples:runLoopRetryUntilValid
 * </pre>
 */
public class LoopRetryUntilValidExample {

    private static final Logger log = LoggerFactory.getLogger(LoopRetryUntilValidExample.class);

    public static void main(String[] args) {
        log.info("=== Loop Retry-Until-Valid Example ===");

        runScenario_convergesOnIteration3();
        runScenario_neverConverges_throws();

        log.info("=== Done ===");
    }

    private static void runScenario_convergesOnIteration3() {
        log.info("--- Scenario 1: validator passes on iteration 3 ---");
        AtomicInteger genAttempts = new AtomicInteger();

        Task generator = Task.builder()
                .name("generator")
                .description("Generate a JSON object")
                .expectedOutput("Valid JSON")
                .handler(ctx -> {
                    int n = genAttempts.incrementAndGet();
                    // First two attempts produce malformed JSON; the third is valid.
                    String body = (n >= 3) ? "{\"ok\":true,\"attempt\":" + n + "}" : "<not json " + n + ">";
                    return ToolResult.success(body);
                })
                .build();

        Task validator = Task.builder()
                .name("validator")
                .description("Validate the generator output is JSON")
                .expectedOutput("VALID or INVALID")
                .handler(ctx -> {
                    String prior = ctx.contextOutputs().isEmpty()
                            ? ""
                            : ctx.contextOutputs().getLast().getRaw();
                    boolean ok = prior.startsWith("{") && prior.endsWith("}");
                    return ToolResult.success(ok ? "VALID" : "INVALID");
                })
                .build();

        Task validatorWithContext =
                validator.toBuilder().context(java.util.List.of(generator)).build();

        Loop loop = Loop.builder()
                .name("retry-json")
                .task(generator)
                .task(validatorWithContext)
                .until(ctx -> ctx.lastBodyOutput().getRaw().equals("VALID"))
                .maxIterations(5)
                .onMaxIterations(MaxIterationsAction.THROW)
                .memoryMode(LoopMemoryMode.ACCUMULATE) // ACCUMULATE here -- we're not using memory scopes
                .build();

        EnsembleOutput out = Ensemble.builder().loop(loop).build().run();

        out.getLoopTerminationReason("retry-json")
                .ifPresent(r -> log.info(
                        "Termination: {} after {} iterations",
                        r,
                        out.getLoopHistory("retry-json").size()));
        log.info("Final output: {}", out.getOutput(generator).orElseThrow().getRaw());
    }

    private static void runScenario_neverConverges_throws() {
        log.info("--- Scenario 2: validator never passes; loop throws ---");
        Task generator = Task.builder()
                .name("generator")
                .description("Generate a JSON object")
                .expectedOutput("Valid JSON")
                .handler(ctx -> ToolResult.success("never valid"))
                .build();

        Task validator = Task.builder()
                .name("validator")
                .description("Validate")
                .expectedOutput("VALID or INVALID")
                .handler(ctx -> ToolResult.success("INVALID"))
                .context(java.util.List.of(generator))
                .build();

        Loop loop = Loop.builder()
                .name("never-valid")
                .task(generator)
                .task(validator)
                .until(ctx -> ctx.lastBodyOutput().getRaw().equals("VALID"))
                .maxIterations(3)
                .onMaxIterations(MaxIterationsAction.THROW)
                .build();

        try {
            Ensemble.builder().loop(loop).build().run();
            log.error("Expected MaxLoopIterationsExceededException but the loop returned normally");
        } catch (MaxLoopIterationsExceededException expected) {
            log.info("Caught expected exception: {}", expected.getMessage());
        }
    }
}
