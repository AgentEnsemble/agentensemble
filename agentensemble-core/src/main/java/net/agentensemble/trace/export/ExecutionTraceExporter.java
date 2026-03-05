package net.agentensemble.trace.export;

import net.agentensemble.trace.ExecutionTrace;

/**
 * Strategy interface for exporting execution traces.
 *
 * <p>Implementations receive a complete {@link ExecutionTrace} after each ensemble run
 * and can write it to any destination: a local file, a remote API, a message queue,
 * a database, etc.
 *
 * <p>Register an exporter on the ensemble:
 * <pre>
 * Ensemble.builder()
 *     .traceExporter(new JsonTraceExporter(Path.of("traces/")))
 *     .build();
 * </pre>
 *
 * <p>Implementations must be thread-safe when the same exporter instance is shared
 * across multiple concurrent ensemble runs.
 */
@FunctionalInterface
public interface ExecutionTraceExporter {

    /**
     * Export the given execution trace.
     *
     * <p>Called by the framework at the end of each successful {@link net.agentensemble.Ensemble#run()}
     * invocation. Only invoked when the run completes without an unhandled exception; runs that
     * fail before producing any output (e.g., validation failures) do not trigger the exporter.
     *
     * @param trace the complete execution trace; never {@code null}
     */
    void export(ExecutionTrace trace);
}
