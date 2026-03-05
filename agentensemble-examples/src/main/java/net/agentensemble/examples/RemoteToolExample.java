package net.agentensemble.examples;

import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.tools.http.HttpAgentTool;
import net.agentensemble.tools.process.ProcessAgentTool;

/**
 * Demonstrates cross-language tool execution using ProcessAgentTool and HttpAgentTool.
 *
 * Prerequisites:
 *
 * 1. Set the OPENAI_API_KEY environment variable.
 *
 * 2. For ProcessAgentTool: create the Python script at /tmp/agentensemble_demo.py:
 *
 *    import sys, json
 *    data = json.loads(sys.stdin.read())
 *    text = data["input"].strip()
 *    words = text.lower().split()
 *    positive = ["good", "great", "excellent", "amazing", "wonderful", "love"]
 *    negative = ["bad", "terrible", "awful", "horrible", "hate", "worst"]
 *    score = sum(1 for w in words if w in positive) - sum(1 for w in words if w in negative)
 *    if score > 0:
 *        sentiment = "positive"
 *    elif score < 0:
 *        sentiment = "negative"
 *    else:
 *        sentiment = "neutral"
 *    print(json.dumps({"output": sentiment, "success": True, "structured": {"sentiment": sentiment, "score": score}}))
 *
 * 3. For HttpAgentTool: start a local HTTP service on port 8080, or update the URL
 *    to point to a real service.
 *
 * Run with:
 *   ./gradlew :agentensemble-examples:run \
 *     -PmainClass=net.agentensemble.examples.RemoteToolExample
 */
public class RemoteToolExample {

    public static void main(String[] args) {
        var chatModel = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o-mini")
                .build();

        // ========================
        // ProcessAgentTool: Python sentiment analyzer
        // ========================
        var sentiment = ProcessAgentTool.builder()
                .name("sentiment_analysis")
                .description("Analyzes the sentiment of a text and returns 'positive', 'negative', "
                        + "or 'neutral'. Input: the text to analyze.")
                .command("python3", "/tmp/agentensemble_demo.py")
                .timeout(Duration.ofSeconds(30))
                .build();

        // ========================
        // HttpAgentTool: word count REST service
        // Replace the URL with a real service or run the Node.js example below.
        //
        // Example Node.js service (save as /tmp/wordcount.js and run with node):
        //   const express = require('express');
        //   const app = express();
        //   app.use(express.text());
        //   app.post('/', (req, res) => res.send(String(req.body.split(/\s+/).length)));
        //   app.listen(8080, () => console.log('Word count service running on :8080'));
        // ========================
        var wordCount = HttpAgentTool.post(
                "word_counter",
                "Counts the number of words in a piece of text. "
                        + "Input: the text to count. Returns the word count as a number.",
                "http://localhost:8080/");

        var agent = Agent.builder()
                .role("Text Analyst")
                .goal("Analyze text using available tools")
                .tools(List.of(sentiment, wordCount))
                .llm(chatModel)
                .maxIterations(5)
                .build();

        var reviewTask = Task.builder()
                .description("Analyze the following product review:\n\n"
                        + "\"The battery life on this laptop is amazing and the screen is excellent. "
                        + "However, the keyboard feels cheap and the trackpad is terrible.\"\n\n"
                        + "Determine the overall sentiment and count the number of words.")
                .expectedOutput("The sentiment (positive/negative/neutral) and word count of the review")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(reviewTask).build().run();

        System.out.println("=== Remote Tool Example Output ===");
        System.out.println(output.getRaw());
    }
}
