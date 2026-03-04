# Remote Tools

AgentEnsemble supports cross-language tool implementations through two remote tool types:
**`ProcessAgentTool`** (subprocess execution) and **`HttpAgentTool`** (REST endpoint wrapping).
Both implement `AgentTool` and are indistinguishable from native Java tools from the agent's perspective.

---

## Why Remote Tools?

- **Python ML models** that are impractical to run in-JVM
- **Existing microservices** you want to expose as agent capabilities
- **Polyglot teams** where tools are maintained in different languages
- **Sandboxing** computationally intensive or security-sensitive operations

---

## ProcessAgentTool

Executes an external subprocess and communicates via the **AgentEnsemble subprocess protocol**.

### Subprocess Protocol Specification

Communication is JSON-over-stdio:

**Input** -- written to the process's stdin immediately after launch:

```json
{"input": "the agent's input string"}
```

**Success output** -- the process writes to stdout before exiting with code 0:

```json
{"output": "result text for the LLM", "success": true}
```

Optionally include a structured payload for programmatic consumers:

```json
{"output": "result text for the LLM", "success": true, "structured": {"key": "value"}}
```

**Failure output** -- on a logical failure (not an exception):

```json
{"error": "description of what went wrong", "success": false}
```

**Non-zero exit code** -- treated as a failure; stderr is captured as the error message.

**Timeout** -- configurable; the process is killed with `destroyForcibly()` if exceeded.

> **Note:** Processes that do not read stdin (e.g., `echo`) are supported. The stdin write
> failure is logged at DEBUG level and execution continues normally.

### Usage

```java
var sentiment = ProcessAgentTool.builder()
    .name("sentiment_analysis")
    .description("Analyzes the sentiment of a piece of text. Returns positive, negative, or neutral.")
    .command("python3", "/opt/tools/sentiment.py")
    .timeout(Duration.ofSeconds(30))
    .build();

var agent = Agent.builder()
    .role("Analyst")
    .goal("Analyze customer feedback")
    .tools(List.of(sentiment))
    .llm(chatModel)
    .build();
```

### Example Python Implementation

```python
import sys
import json

def analyze(text):
    # Replace with real sentiment logic
    words = text.lower().split()
    if any(w in words for w in ["good", "great", "excellent"]):
        return "positive"
    elif any(w in words for w in ["bad", "terrible", "awful"]):
        return "negative"
    return "neutral"

data = json.loads(sys.stdin.read())
result = analyze(data["input"])
print(json.dumps({"output": result, "success": True}))
```

### Builder Options

| Option        | Required | Default    | Description                                |
|---------------|----------|------------|--------------------------------------------|
| `name`        | Yes      | -          | Tool name shown to the LLM                 |
| `description` | Yes      | -          | Tool description shown to the LLM          |
| `command`     | Yes      | -          | Program and arguments (varargs or List)    |
| `timeout`     | No       | 30 seconds | Maximum execution time before process kill |

---

## HttpAgentTool

Wraps an HTTP endpoint as an agent tool. No subprocess overhead -- uses Java's built-in
`HttpClient`.

### Request Format

- **GET** requests: input appended as a query parameter -- `?input=<url-encoded-input>`
- **POST** requests: input sent as the request body
    - `Content-Type: application/json` when input is valid JSON
    - `Content-Type: text/plain; charset=UTF-8` otherwise

### Response Format

The HTTP response body is returned as the tool's plain-text output to the agent.
HTTP 4xx/5xx responses are treated as failures.

### Usage

#### GET endpoint (simple search API)

```java
var search = HttpAgentTool.get(
    "knowledge_base_search",
    "Searches the internal knowledge base for relevant articles.",
    "https://kb.example.com/search");
```

#### POST endpoint with custom headers

```java
var classifier = HttpAgentTool.builder()
    .name("text_classifier")
    .description("Classifies text into categories: tech, finance, sports, other.")
    .url("https://ml.example.com/classify")
    .method("POST")
    .header("Authorization", "Bearer " + System.getenv("ML_API_KEY"))
    .timeout(Duration.ofSeconds(60))
    .build();
```

#### POST endpoint with JSON input

```java
// When the agent provides JSON input, application/json Content-Type is set automatically
var enricher = HttpAgentTool.post(
    "entity_enricher",
    "Enriches entity data given a JSON object with 'name' and 'type' fields.",
    "https://api.example.com/enrich");
```

### Builder Options

| Option        | Required | Default    | Description                                     |
|---------------|----------|------------|-------------------------------------------------|
| `name`        | Yes      | -          | Tool name shown to the LLM                      |
| `description` | Yes      | -          | Tool description shown to the LLM               |
| `url`         | Yes      | -          | The HTTP endpoint URL                           |
| `method`      | No       | `POST`     | HTTP method (GET, POST, PUT, etc.)              |
| `header`      | No       | (none)     | Add a request header (can be called multiple times) |
| `timeout`     | No       | 30 seconds | Request timeout                                 |

### Example Node.js Service

```javascript
const express = require('express');
const app = express();
app.use(express.text());

app.post('/classify', (req, res) => {
    const input = req.body;
    const category = classify(input);  // your logic here
    res.send(category);
});

app.listen(8080);
```

---

## Mixing Native and Remote Tools

Tools of all types can be combined freely on a single agent:

```java
var agent = Agent.builder()
    .role("Data Analyst")
    .goal("Analyze sales data using all available tools")
    .tools(List.of(
        new CalculatorTool(),                                   // Java (in-process)
        ProcessAgentTool.builder()                             // Python subprocess
            .name("forecast")
            .description("Generates a sales forecast")
            .command("python3", "/opt/tools/forecast.py")
            .build(),
        HttpAgentTool.get(                                     // REST API
            "product_data",
            "Retrieves product catalog data",
            "https://api.example.com/products")
    ))
    .llm(chatModel)
    .build();
```

---

## Parallel Execution

When the LLM requests multiple tools in a single turn, AgentEnsemble executes them
concurrently using the configured tool executor (default: virtual threads). This is
especially beneficial for remote tools where I/O latency dominates:

```java
// A parallel tool turn (calculator + Python forecast + HTTP API) runs concurrently
Ensemble.builder()
    .agent(agent)
    .task(task)
    .toolExecutor(Executors.newVirtualThreadPerTaskExecutor()) // default
    .build()
    .run();
```

For rate-limited APIs, provide a bounded executor to cap concurrency:

```java
Ensemble.builder()
    .agent(agent)
    .task(task)
    .toolExecutor(Executors.newFixedThreadPool(4))
    .build()
    .run();
```
