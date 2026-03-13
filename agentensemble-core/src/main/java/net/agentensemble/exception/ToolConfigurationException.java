package net.agentensemble.exception;

/**
 * Thrown when a tool is misconfigured at execution time and cannot proceed safely.
 *
 * <p>Common causes:
 * <ul>
 *   <li>A tool configured with {@code requireApproval(true)} is invoked without a
 *       {@link net.agentensemble.review.ReviewHandler} registered on the ensemble.</li>
 *   <li>The {@code agentensemble-review} module is absent from the runtime classpath
 *       when a tool attempts to call
 *       {@link net.agentensemble.tool.AbstractAgentTool#requestApproval(String)}.</li>
 *   <li>The {@link net.agentensemble.tool.ToolContext} carries an object that is not
 *       a valid {@link net.agentensemble.review.ReviewHandler}.</li>
 * </ul>
 *
 * <p>Extends {@link IllegalStateException} so that call sites that catch
 * {@code IllegalStateException} continue to work. The dedicated subtype allows
 * {@link net.agentensemble.tool.AbstractAgentTool#execute(String)} and
 * {@link net.agentensemble.tool.LangChain4jToolAdapter#executeForResult} to re-throw
 * only configuration errors -- not ordinary runtime {@code IllegalStateException} values
 * that should be reported as {@link net.agentensemble.tool.ToolResult#failure(String)}
 * so the agent can adapt.
 */
public class ToolConfigurationException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct a ToolConfigurationException with a detail message.
     *
     * @param message the detail message
     */
    public ToolConfigurationException(String message) {
        super(message);
    }

    /**
     * Construct a ToolConfigurationException with a detail message and a cause.
     *
     * @param message the detail message
     * @param cause   the cause; may be {@code null}
     */
    public ToolConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
