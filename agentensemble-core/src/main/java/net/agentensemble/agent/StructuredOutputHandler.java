package net.agentensemble.agent;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.exception.OutputParsingException;
import net.agentensemble.output.JsonSchemaGenerator;
import net.agentensemble.output.ParseResult;
import net.agentensemble.output.StructuredOutputParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles structured output parsing and LLM retry logic for agent task execution.
 *
 * Package-private. Called by {@link AgentExecutor} when the task declares an
 * {@code outputType}.
 */
class StructuredOutputHandler {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputHandler.class);

    /**
     * Attempt to parse the agent's response into the structured type declared on the task.
     *
     * Runs up to {@code task.maxOutputRetries + 1} attempts. On each failure the LLM
     * is shown the parse error and the required JSON schema and asked to produce a
     * corrected response. If all attempts fail, {@link OutputParsingException} is thrown.
     *
     * @param agent           the agent that produced the response (used for retry LLM calls)
     * @param task            the task containing outputType and maxOutputRetries
     * @param initialResponse the raw LLM response from the main execution path
     * @param systemPrompt    the system prompt used during the original execution
     * @return the parsed object (never null on success)
     * @throws OutputParsingException if all parse attempts are exhausted
     */
    static Object parse(Agent agent, Task task, String initialResponse, String systemPrompt) {

        List<String> parseErrors = new ArrayList<>();
        String currentResponse = initialResponse;
        Class<?> outputType = task.getOutputType();
        int maxRetries = task.getMaxOutputRetries();
        String schemaDescription = JsonSchemaGenerator.generate(outputType);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            ParseResult<?> result = StructuredOutputParser.parse(currentResponse, outputType);
            if (result.isSuccess()) {
                log.info(
                        "Agent '{}' structured output parsed successfully on attempt {}/{}",
                        agent.getRole(),
                        attempt + 1,
                        maxRetries + 1);
                return result.getValue();
            }

            parseErrors.add(result.getErrorMessage());

            if (attempt == maxRetries) {
                break;
            }

            log.warn(
                    "Agent '{}' structured output parse failed (attempt {}/{}): {}",
                    agent.getRole(),
                    attempt + 1,
                    maxRetries + 1,
                    result.getErrorMessage());

            String correctionPrompt =
                    buildCorrectionPrompt(currentResponse, result.getErrorMessage(), schemaDescription);

            ChatRequest retryRequest = ChatRequest.builder()
                    .messages(List.of(new SystemMessage(systemPrompt), new UserMessage(correctionPrompt)))
                    .build();

            ChatResponse retryResponse = agent.getLlm().chat(retryRequest);
            currentResponse = retryResponse.aiMessage().text();
        }

        // Pass currentResponse (the last bad response) so the exception carries the
        // most relevant output for debugging -- not initialResponse from attempt 0.
        throw new OutputParsingException(
                "Structured output parsing failed for task '"
                        + truncate(task.getDescription(), 80)
                        + "' after " + parseErrors.size() + " attempt(s). "
                        + "Expected type: " + outputType.getSimpleName(),
                currentResponse,
                outputType,
                parseErrors,
                parseErrors.size());
    }

    private static String buildCorrectionPrompt(String badOutput, String errorMessage, String schemaDescription) {
        return "Your previous response could not be parsed as valid JSON.\n\n"
                + "Error: " + errorMessage + "\n\n"
                + "Your previous response was:\n" + badOutput + "\n\n"
                + "You MUST respond with ONLY valid JSON matching this schema "
                + "(it may be an object, array, string, number, boolean, or null as required):\n"
                + schemaDescription + "\n\n"
                + "Do not include any explanation, markdown fences, or text before or after "
                + "the JSON. Respond with only the JSON value.";
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
