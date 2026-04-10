package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Client-to-server message for controlling an in-progress ensemble run.
 *
 * <p>Supported actions:
 * <ul>
 *   <li>{@code "cancel"} -- cooperatively cancel the run at the next task boundary</li>
 *   <li>{@code "switch_model"} -- switch the active LLM to the given model alias immediately;
 *       takes effect on the next LLM call</li>
 * </ul>
 *
 * @param runId  the run to control; must not be null
 * @param action the control action: {@code "cancel"} or {@code "switch_model"}; must not be null
 * @param model  the model catalog alias to switch to; required for {@code switch_model},
 *               ignored for {@code cancel}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunControlMessage(String runId, String action, String model) implements ClientMessage {}
