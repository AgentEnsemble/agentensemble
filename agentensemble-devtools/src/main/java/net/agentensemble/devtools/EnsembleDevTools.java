package net.agentensemble.devtools;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.agentensemble.Ensemble;
import net.agentensemble.devtools.dag.DagExporter;
import net.agentensemble.devtools.dag.DagModel;
import net.agentensemble.ensemble.EnsembleOutput;

/**
 * Convenience facade for developer tooling operations on AgentEnsemble objects.
 *
 * <p>Provides methods to export pre-execution dependency graphs and post-execution traces
 * in formats consumable by the {@code agentensemble-viz} trace viewer.
 *
 * <h2>Typical usage</h2>
 *
 * <pre>
 * Ensemble ensemble = Ensemble.builder()
 *     .agent(researcher)
 *     .agent(writer)
 *     .task(researchTask)
 *     .task(writeTask)
 *     .workflow(Workflow.PARALLEL)
 *     .captureMode(CaptureMode.STANDARD)
 *     .build();
 *
 * // 1. Export the planned dependency graph BEFORE running
 * EnsembleDevTools.exportDag(ensemble, Path.of("./traces/"));
 *
 * // 2. Run the ensemble
 * EnsembleOutput output = ensemble.run(Map.of("topic", "AI agents"));
 *
 * // 3. Export the full execution trace AFTER running
 * EnsembleDevTools.exportTrace(output, Path.of("./traces/"));
 *
 * // Or: export both in one call
 * EnsembleDevTools.export(ensemble, output, Path.of("./traces/"));
 * </pre>
 *
 * <p>Load the exported files in the {@code agentensemble-viz} viewer:
 *
 * <pre>
 * npx {@literal @}agentensemble/viz ./traces/
 * </pre>
 *
 * <p>This class has no instance state; all methods are static.
 */
public final class EnsembleDevTools {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private EnsembleDevTools() {}

    // ========================
    // DAG export
    // ========================

    /**
     * Build a pre-execution {@link DagModel} from the given ensemble configuration.
     *
     * <p>Does not execute the ensemble. The returned model captures the task dependency
     * structure, agent configurations, topological levels (parallel groups), and critical path.
     *
     * @param ensemble the ensemble to analyze; must not be {@code null}
     * @return the computed {@link DagModel}
     * @throws IllegalArgumentException if ensemble is null or has no tasks
     */
    public static DagModel buildDag(Ensemble ensemble) {
        return DagExporter.build(ensemble);
    }

    /**
     * Export the pre-execution dependency graph to a {@code .dag.json} file in the given
     * output directory.
     *
     * <p>The file is named {@code ensemble-dag-<timestamp>.dag.json} where {@code timestamp}
     * is the current UTC time formatted as {@code yyyyMMdd-HHmmss}.
     *
     * <p>The output directory is created if it does not already exist.
     *
     * @param ensemble  the ensemble to analyze; must not be {@code null}
     * @param outputDir the directory to write the file to; must not be {@code null}
     * @return the path of the written file
     * @throws IllegalArgumentException if ensemble is null or has no tasks
     */
    public static Path exportDag(Ensemble ensemble, Path outputDir) {
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        DagModel dag = DagExporter.build(ensemble);
        String timestamp = FILE_TIMESTAMP.format(Instant.now());
        Path outputPath = outputDir.resolve("ensemble-dag-" + timestamp + ".dag.json");
        dag.toJson(outputPath);
        return outputPath;
    }

    // ========================
    // Trace export
    // ========================

    /**
     * Export the post-execution trace from the given {@link EnsembleOutput} to a
     * {@code .trace.json} file in the given output directory.
     *
     * <p>Requires that the ensemble was run with {@code captureMode} set to
     * {@link net.agentensemble.trace.CaptureMode#STANDARD} or higher to include LLM
     * conversation history and memory operation details. The trace is always available
     * (at the basic level) regardless of capture mode.
     *
     * <p>The file is named {@code ensemble-trace-<timestamp>.trace.json}.
     *
     * @param output    the output of a completed ensemble run; must not be {@code null}
     * @param outputDir the directory to write the file to; must not be {@code null}
     * @return the path of the written file
     * @throws IllegalArgumentException if output is null or has no trace
     */
    public static Path exportTrace(EnsembleOutput output, Path outputDir) {
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir must not be null");
        }
        if (output.getTrace() == null) {
            throw new IllegalArgumentException(
                    "output has no execution trace attached; this usually means the EnsembleOutput"
                            + " was not produced by Ensemble.run() or comes from an older"
                            + " AgentEnsemble version");
        }
        String timestamp = FILE_TIMESTAMP.format(Instant.now());
        Path outputPath = outputDir.resolve("ensemble-trace-" + timestamp + ".trace.json");
        output.getTrace().toJson(outputPath);
        return outputPath;
    }

    // ========================
    // Convenience: export both
    // ========================

    /**
     * Export both the pre-execution DAG and the post-execution trace in a single call.
     *
     * <p>Both files are written to the same output directory with their respective
     * {@code .dag.json} and {@code .trace.json} extensions.
     *
     * @param ensemble  the ensemble that was configured and run; must not be {@code null}
     * @param output    the output of the completed ensemble run; must not be {@code null}
     * @param outputDir the directory to write the files to; must not be {@code null}
     * @return a result containing the paths of both written files
     */
    public static ExportResult export(Ensemble ensemble, EnsembleOutput output, Path outputDir) {
        Path dagPath = exportDag(ensemble, outputDir);
        Path tracePath = exportTrace(output, outputDir);
        return new ExportResult(dagPath, tracePath);
    }

    /**
     * Result of a combined {@link #export(Ensemble, EnsembleOutput, Path)} call.
     *
     * @param dagPath   path to the written {@code .dag.json} file
     * @param tracePath path to the written {@code .trace.json} file
     */
    public record ExportResult(Path dagPath, Path tracePath) {

        /**
         * Return a human-readable summary of both written file paths.
         *
         * @return formatted string describing the export result
         */
        public String describe() {
            return "DAG exported to:   " + dagPath + "\nTrace exported to: " + tracePath;
        }
    }
}
