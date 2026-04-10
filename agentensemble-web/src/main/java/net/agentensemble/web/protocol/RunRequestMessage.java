package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Client-to-server WebSocket message for submitting an ensemble run.
 *
 * <p>Supports three levels of parameterization (matching {@code POST /api/runs}):
 * <ul>
 *   <li><strong>Level 1</strong> -- {@code inputs} only; the template ensemble runs as-is with
 *       variable substitution.</li>
 *   <li><strong>Level 2</strong> -- {@code inputs} + {@code taskOverrides}; override specific
 *       task fields (description, model, tools, maxIterations, etc.) at runtime.</li>
 *   <li><strong>Level 3</strong> -- {@code inputs} + {@code tasks}; define an entirely new task
 *       list without modifying Java code.</li>
 * </ul>
 *
 * <p>The server responds immediately with a {@link RunAckMessage} ({@code run_ack}) and, on
 * completion, sends a {@link RunResultMessage} ({@code run_result}) targeted to the originating
 * WebSocket session only.
 *
 * @param requestId     caller-assigned identifier echoed in {@link RunAckMessage}; may be null
 * @param inputs        template variable substitutions ({@code {variable}} placeholders); may be null
 * @param tasks         Level 3 dynamic task definitions; when present, {@code taskOverrides} is
 *                      ignored and the template's task list is fully replaced
 * @param taskOverrides Level 2 per-task override fields keyed by task name or description prefix;
 *                      ignored when {@code tasks} is present
 * @param options       per-run execution overrides (e.g. {@code maxToolOutputLength}); may be null
 * @param tags          arbitrary key-value tags attached to the run state; may be null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunRequestMessage(
        String requestId,
        Map<String, String> inputs,
        List<Map<String, Object>> tasks,
        Map<String, Map<String, Object>> taskOverrides,
        Map<String, Object> options,
        Map<String, String> tags)
        implements ClientMessage {}
