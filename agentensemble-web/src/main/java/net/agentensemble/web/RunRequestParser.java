package net.agentensemble.web;

import java.util.Map;
import java.util.Objects;
import net.agentensemble.Ensemble;
import net.agentensemble.execution.RunOptions;

/**
 * Converts API run requests into {@link RunConfiguration} values ready for execution.
 *
 * <p>In Phase 1 (this implementation), only <strong>Level 1</strong> is supported:
 * run the pre-configured template ensemble with input variable substitution. The
 * template ensemble's tasks run as-is; {@code {variable}} placeholders in task
 * descriptions are resolved by the {@code TemplateResolver} inside
 * {@code Ensemble.run(inputs, options)}.
 *
 * <p>Future phases will add:
 * <ul>
 *   <li>Level 2 -- per-task overrides (description, model, tool add/remove)</li>
 *   <li>Level 3 -- fully dynamic task creation from a JSON task list</li>
 * </ul>
 *
 * <p>Instances are stateless and safe for concurrent use.
 */
public final class RunRequestParser {

    private final ToolCatalog toolCatalog;
    private final ModelCatalog modelCatalog;

    /**
     * Creates a parser with the given catalog references.
     *
     * <p>Catalogs may be {@code null} in Phase 1 (Level 1 parsing does not resolve
     * tools or models from catalogs). They are retained here for Phase 2+ use.
     *
     * @param toolCatalog  the registered tool allowlist; may be null
     * @param modelCatalog the registered model allowlist; may be null
     */
    public RunRequestParser(ToolCatalog toolCatalog, ModelCatalog modelCatalog) {
        this.toolCatalog = toolCatalog;
        this.modelCatalog = modelCatalog;
    }

    /**
     * Level 1: builds a run configuration from a template ensemble and input variables.
     *
     * <p>The template's tasks are run unchanged; {@code {variable}} placeholders are
     * resolved by the ensemble's own {@code TemplateResolver} at run time.
     *
     * @param template the pre-configured template ensemble; must not be null
     * @param inputs   the input variables to substitute; may be null (treated as empty)
     * @param options  per-run execution overrides; may be null (uses ensemble defaults)
     * @return a {@link RunConfiguration} ready for execution via {@link RunManager}
     * @throws NullPointerException if {@code template} is null
     */
    public RunConfiguration buildFromTemplate(Ensemble template, Map<String, String> inputs, RunOptions options) {
        Objects.requireNonNull(template, "template ensemble must not be null");
        Map<String, String> effectiveInputs = inputs != null ? Map.copyOf(inputs) : Map.of();
        RunOptions effectiveOptions = options != null ? options : RunOptions.DEFAULT;
        return new RunConfiguration(template, effectiveInputs, effectiveOptions);
    }

    /**
     * Returns the tool catalog, or {@code null} if not configured.
     */
    public ToolCatalog getToolCatalog() {
        return toolCatalog;
    }

    /**
     * Returns the model catalog, or {@code null} if not configured.
     */
    public ModelCatalog getModelCatalog() {
        return modelCatalog;
    }

    /**
     * A validated, ready-to-execute run configuration produced by {@link RunRequestParser}.
     *
     * @param template the template ensemble to run
     * @param inputs   the resolved input variables (never null, may be empty)
     * @param options  the effective run options (never null)
     */
    public record RunConfiguration(Ensemble template, Map<String, String> inputs, RunOptions options) {}
}
