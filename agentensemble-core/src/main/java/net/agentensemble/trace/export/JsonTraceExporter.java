package net.agentensemble.trace.export;

import java.nio.file.Path;
import java.util.Objects;
import net.agentensemble.trace.ExecutionTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link ExecutionTraceExporter} that writes each trace to a JSON file.
 *
 * <p>Two modes are supported:
 * <ul>
 *   <li><strong>Directory mode</strong>: Provide a directory path. Each run produces a new file
 *       named {@code {ensembleId}.json} inside that directory. The directory is created if it
 *       does not already exist.</li>
 *   <li><strong>File mode</strong>: Provide a specific file path. Each run overwrites that file.
 *       Useful for single-run pipelines or when the caller manages file naming externally.</li>
 * </ul>
 *
 * <h2>Examples</h2>
 *
 * <pre>
 * // Directory mode: traces/abc123-....json, traces/def456-....json, ...
 * Ensemble.builder()
 *     .traceExporter(new JsonTraceExporter(Path.of("traces")))
 *     .build();
 *
 * // File mode: always writes to run-trace.json
 * Ensemble.builder()
 *     .traceExporter(new JsonTraceExporter(Path.of("run-trace.json"), false))
 *     .build();
 * </pre>
 *
 * <p>This class is thread-safe when used in directory mode. In file mode, concurrent runs
 * will race to write the same file.
 */
public class JsonTraceExporter implements ExecutionTraceExporter {

    private static final Logger log = LoggerFactory.getLogger(JsonTraceExporter.class);

    private final Path outputPath;
    private final boolean directoryMode;

    /**
     * Create a directory-mode exporter.
     *
     * <p>Each export writes a new file named {@code {ensembleId}.json} inside
     * {@code outputDirectory}. The directory is created if it does not exist.
     *
     * @param outputDirectory the directory to write trace files into; must not be {@code null}
     */
    public JsonTraceExporter(Path outputDirectory) {
        this(outputDirectory, true);
    }

    /**
     * Create an exporter with explicit mode selection.
     *
     * @param outputPath    when {@code directoryMode} is {@code true}, a directory path;
     *                      when {@code false}, the exact file path to write
     * @param directoryMode {@code true} to auto-name files by ensemble ID; {@code false} to
     *                      always write to {@code outputPath}
     */
    public JsonTraceExporter(Path outputPath, boolean directoryMode) {
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
        this.directoryMode = directoryMode;
    }

    @Override
    public void export(ExecutionTrace trace) {
        Path target = resolveTarget(trace);
        try {
            trace.toJson(target);
            log.debug("Execution trace written to {}", target);
        } catch (Exception e) {
            log.warn("Failed to export execution trace to {}: {}", target, e.getMessage(), e);
        }
    }

    private Path resolveTarget(ExecutionTrace trace) {
        if (!directoryMode) {
            return outputPath;
        }
        // Build a filename: {ensembleId}.json
        String filename = trace.getEnsembleId() + ".json";
        return outputPath.resolve(filename);
    }
}
