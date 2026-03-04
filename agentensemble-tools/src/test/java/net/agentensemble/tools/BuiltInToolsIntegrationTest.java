package net.agentensemble.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration tests verifying that built-in tools implement the AgentTool contract correctly
 * and interoperate with core types (AgentTool, ToolResult).
 *
 * <p>These tests exercise tool combinations that an agent would typically use together,
 * without making real network calls or requiring an LLM.
 */
class BuiltInToolsIntegrationTest {

    @TempDir
    Path tempDir;

    // --- Tools implement AgentTool ---

    @Test
    void allTools_implementAgentToolInterface() {
        AgentTool calculator = new CalculatorTool();
        AgentTool dateTime = new DateTimeTool();
        AgentTool fileRead = FileReadTool.of(tempDir);
        AgentTool fileWrite = FileWriteTool.of(tempDir);
        AgentTool webSearch = WebSearchTool.of(query -> "mock results for: " + query);
        AgentTool webScrape = new WebScraperTool();
        AgentTool jsonParser = new JsonParserTool();

        assertThat(calculator.name()).isNotBlank();
        assertThat(dateTime.name()).isNotBlank();
        assertThat(fileRead.name()).isNotBlank();
        assertThat(fileWrite.name()).isNotBlank();
        assertThat(webSearch.name()).isNotBlank();
        assertThat(webScrape.name()).isNotBlank();
        assertThat(jsonParser.name()).isNotBlank();

        assertThat(calculator.description()).isNotBlank();
        assertThat(dateTime.description()).isNotBlank();
        assertThat(fileRead.description()).isNotBlank();
        assertThat(fileWrite.description()).isNotBlank();
        assertThat(webSearch.description()).isNotBlank();
        assertThat(webScrape.description()).isNotBlank();
        assertThat(jsonParser.description()).isNotBlank();
    }

    // --- Calculator + DateTimeTool scenario ---

    @Test
    void calculatorAndDateTime_agentScenario() {
        // Scenario: an agent calculates how many days until end of year, then formats a result
        CalculatorTool calculator = new CalculatorTool();
        DateTimeTool dateTime = new DateTimeTool(Clock.fixed(Instant.parse("2024-03-15T12:00:00Z"), ZoneId.of("UTC")));

        // Agent uses calculator to compute days remaining in March
        ToolResult calcResult = calculator.execute("31 - 15");
        assertThat(calcResult.isSuccess()).isTrue();
        assertThat(calcResult.getOutput()).isEqualTo("16");

        // Agent uses dateTime to get today's date
        ToolResult dateResult = dateTime.execute("today");
        assertThat(dateResult.isSuccess()).isTrue();
        assertThat(dateResult.getOutput()).isEqualTo("2024-03-15");

        // Agent uses dateTime arithmetic to find date N days later
        ToolResult futureDate = dateTime.execute("2024-03-15 + 16 days");
        assertThat(futureDate.isSuccess()).isTrue();
        assertThat(futureDate.getOutput()).isEqualTo("2024-03-31");
    }

    // --- FileWrite + FileRead round-trip scenario ---

    @Test
    void fileWriteAndFileRead_roundTrip() throws IOException {
        FileWriteTool writer = FileWriteTool.of(tempDir);
        FileReadTool reader = FileReadTool.of(tempDir);

        // Agent writes a report
        String report = "Analysis complete. Key findings:\n1. Revenue up 12%\n2. Costs stable.";
        ToolResult writeResult =
                writer.execute("{\"path\": \"report.txt\", \"content\": \"" + report.replace("\n", "\\n") + "\"}");
        assertThat(writeResult.isSuccess()).isTrue();

        // Agent reads it back
        ToolResult readResult = reader.execute("report.txt");
        assertThat(readResult.isSuccess()).isTrue();
        assertThat(readResult.getOutput()).isEqualTo(report);
    }

    // --- JsonParser + FileRead scenario ---

    @Test
    void jsonParserAndFileWrite_dataProcessingScenario() throws IOException {
        JsonParserTool parser = new JsonParserTool();
        FileWriteTool writer = FileWriteTool.of(tempDir);

        // Agent parses a JSON API response to extract relevant data
        String apiResponse = "{\"status\": \"ok\", \"data\": {\"count\": 42, \"label\": \"results\"}}";

        ToolResult countResult = parser.execute("data.count\n" + apiResponse);
        assertThat(countResult.isSuccess()).isTrue();
        assertThat(countResult.getOutput()).isEqualTo("42");

        ToolResult labelResult = parser.execute("data.label\n" + apiResponse);
        assertThat(labelResult.isSuccess()).isTrue();
        assertThat(labelResult.getOutput()).isEqualTo("results");

        // Agent writes the extracted data to a file
        String summary = "Count: " + countResult.getOutput() + ", Label: " + labelResult.getOutput();
        ToolResult writeResult = writer.execute("{\"path\": \"summary.txt\", \"content\": \"" + summary + "\"}");
        assertThat(writeResult.isSuccess()).isTrue();

        // Verify the file contents
        assertThat(Files.readString(tempDir.resolve("summary.txt"))).isEqualTo(summary);
    }

    // --- WebSearch + Calculator scenario ---

    @Test
    void webSearchAndCalculator_researchScenario() throws IOException, InterruptedException {
        // Mock search provider returns structured data
        WebSearchTool searcher = WebSearchTool.of(query -> {
            if (query.contains("Java market")) {
                return "1. Java market share: 35%\n2. Python: 28%\n3. JavaScript: 25%";
            }
            return "No results.";
        });

        ToolResult searchResult = searcher.execute("Java market share statistics");
        assertThat(searchResult.isSuccess()).isTrue();
        assertThat(searchResult.getOutput()).contains("35%");

        // Agent uses calculator to process the numbers
        ToolResult sumResult = new CalculatorTool().execute("35 + 28 + 25");
        assertThat(sumResult.isSuccess()).isTrue();
        assertThat(sumResult.getOutput()).isEqualTo("88");

        ToolResult remainder = new CalculatorTool().execute("100 - 88");
        assertThat(remainder.isSuccess()).isTrue();
        assertThat(remainder.getOutput()).isEqualTo("12");
    }

    // --- Tool failure results are ToolResult.failure ---

    @Test
    void allTools_returnFailureOnInvalidInput() {
        var tools = new AgentTool[] {
            new CalculatorTool(),
            new DateTimeTool(),
            FileReadTool.of(tempDir),
            FileWriteTool.of(tempDir),
            WebSearchTool.of(q -> "ok"),
            new WebScraperTool(100, 1, url -> {
                throw new IOException("simulated");
            }),
            new JsonParserTool()
        };

        for (AgentTool tool : tools) {
            ToolResult nullResult = tool.execute(null);
            assertThat(nullResult.isSuccess())
                    .as("Tool %s should return failure for null input", tool.name())
                    .isFalse();
            assertThat(nullResult.getErrorMessage())
                    .as("Tool %s should have a non-null error message for null input", tool.name())
                    .isNotNull();

            ToolResult blankResult = tool.execute("  ");
            assertThat(blankResult.isSuccess())
                    .as("Tool %s should return failure for blank input", tool.name())
                    .isFalse();
        }
    }
}
