# Built-in Tools

AgentEnsemble provides a library of ready-to-use tools. Each tool is published as its own
Gradle/Maven module so you can include exactly what your project needs.

All built-in tools extend `AbstractAgentTool` and receive automatic metrics, structured
logging, and exception handling. See [Tools](tools.md) and [Metrics](metrics.md) for details.

---

## Installation

### Individual tools (recommended)

Add only the tools you need:

=== "Gradle"

    ```kotlin
    implementation("net.agentensemble:agentensemble-tools-calculator:VERSION")
    implementation("net.agentensemble:agentensemble-tools-datetime:VERSION")
    implementation("net.agentensemble:agentensemble-tools-json-parser:VERSION")
    implementation("net.agentensemble:agentensemble-tools-file-read:VERSION")
    implementation("net.agentensemble:agentensemble-tools-file-write:VERSION")
    implementation("net.agentensemble:agentensemble-tools-web-search:VERSION")
    implementation("net.agentensemble:agentensemble-tools-web-scraper:VERSION")
    // Remote tools
    implementation("net.agentensemble:agentensemble-tools-process:VERSION")
    implementation("net.agentensemble:agentensemble-tools-http:VERSION")
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>net.agentensemble</groupId>
      <artifactId>agentensemble-tools-calculator</artifactId>
      <version>VERSION</version>
    </dependency>
    ```

### BOM (Bill of Materials)

Use the BOM to manage all tool versions in one place:

=== "Gradle"

    ```kotlin
    implementation(platform("net.agentensemble:agentensemble-tools-bom:VERSION"))
    implementation("net.agentensemble:agentensemble-tools-calculator")
    implementation("net.agentensemble:agentensemble-tools-web-search")
    // ... no version needed; from BOM
    ```

=== "Maven"

    ```xml
    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>net.agentensemble</groupId>
          <artifactId>agentensemble-tools-bom</artifactId>
          <version>VERSION</version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
      </dependencies>
    </dependencyManagement>
    ```

---

## Available Tools

### CalculatorTool

**Module:** `agentensemble-tools-calculator` |
**Package:** `net.agentensemble.tools.calculator`

Evaluates arithmetic expressions with full operator precedence.

**Supported operators:** `+`, `-`, `*`, `/`, `%` (modulo), `^` (power), parentheses, unary minus

```java
import net.agentensemble.tools.calculator.CalculatorTool;

var calculator = new CalculatorTool();
agent.tools(List.of(calculator));
```

**Input examples:**
- `"2 + 3 * 4"` → `14`
- `"(10 + 5) / 3"` → `5`
- `"2 ^ 10"` → `1024`

---

### DateTimeTool

**Module:** `agentensemble-tools-datetime` |
**Package:** `net.agentensemble.tools.datetime`

Date and time operations using the Java `java.time` package.

```java
import net.agentensemble.tools.datetime.DateTimeTool;

var datetime = new DateTimeTool();
```

**Supported commands:**

| Command | Example | Result |
|---------|---------|--------|
| `now` | `now` | Current UTC datetime |
| `now in <timezone>` | `now in America/New_York` | Current datetime in timezone |
| `today` | `today` | Current UTC date |
| `today in <timezone>` | `today in Europe/London` | Current date in timezone |
| `<date> +/- <N> <unit>` | `2024-01-15 + 30 days` | Date arithmetic |
| `<datetime> +/- <N> <unit>` | `2024-01-15T10:00:00Z + 2 hours` | Datetime arithmetic |
| `convert <dt> from <tz> to <tz>` | `convert 2024-01-15T10:00:00Z from UTC to America/Chicago` | Timezone conversion |

Units: `days`, `weeks`, `months`, `years`, `hours`, `minutes`, `seconds`

---

### JsonParserTool

**Module:** `agentensemble-tools-json-parser` |
**Package:** `net.agentensemble.tools.json`

Extracts values from JSON using dot-notation paths with array indexing.

```java
import net.agentensemble.tools.json.JsonParserTool;

var jsonParser = new JsonParserTool();
```

**Input format** (two lines: path, then JSON):

```
user.address.city
{"user": {"name": "Alice", "address": {"city": "Denver"}}}
```

**Path syntax:**
- `user.name` -- nested object access
- `items[0].title` -- array index access
- `metadata.tags[2]` -- combined

---

### FileReadTool

**Module:** `agentensemble-tools-file-read` |
**Package:** `net.agentensemble.tools.io`

Reads file contents within a sandboxed base directory. Path traversal (`../`) is rejected.

```java
import net.agentensemble.tools.io.FileReadTool;

var fileRead = FileReadTool.of(Path.of("/workspace/data"));
```

**Input:** a relative file path, e.g. `"reports/q4.txt"` or `"data/summary.json"`

---

### FileWriteTool

**Module:** `agentensemble-tools-file-write` |
**Package:** `net.agentensemble.tools.io`

Writes content to a file within a sandboxed base directory. Parent directories are
created automatically.

```java
import net.agentensemble.tools.io.FileWriteTool;

var fileWrite = FileWriteTool.of(Path.of("/workspace/output"));
```

**Input format (JSON):**

```json
{"path": "reports/analysis.md", "content": "# Analysis\n\nThe results show..."}
```

---

### WebSearchTool

**Module:** `agentensemble-tools-web-search` |
**Package:** `net.agentensemble.tools.web.search`

Performs web searches using Tavily or SerpAPI.

```java
import net.agentensemble.tools.web.search.WebSearchTool;

// Tavily
var search = WebSearchTool.ofTavily(System.getenv("TAVILY_API_KEY"));

// SerpAPI (Google)
var search = WebSearchTool.ofSerpApi(System.getenv("SERPAPI_KEY"));

// Custom provider
var search = WebSearchTool.of(myCustomProvider);
```

**Input:** a search query string, e.g. `"Java 21 virtual threads performance benchmark"`

---

### WebScraperTool

**Module:** `agentensemble-tools-web-scraper` |
**Package:** `net.agentensemble.tools.web.scraper`

Fetches a web page and extracts its readable text content by stripping HTML, scripts,
navigation, and footer elements.

```java
import net.agentensemble.tools.web.scraper.WebScraperTool;

// Default settings (5000 chars, 10s timeout)
var scraper = new WebScraperTool();

// Custom max content length
var scraper = WebScraperTool.withMaxContentLength(3000);
```

**Input:** a URL string, e.g. `"https://example.com/article"`

> **Security note:** `WebScraperTool` will attempt to fetch whatever URL string it is given.
> When the URL comes from untrusted input (for example, an end user or an LLM prompt injection),
> this can create a server-side request forgery (SSRF) risk, allowing access to internal services
> such as cloud metadata endpoints or intra-VPC APIs. In untrusted contexts, validate and restrict
> URLs before passing them to the tool (for example, allowlisting schemes and external hostnames
> and blocking private/link-local address ranges), or route requests through a hardened URL
> fetcher/proxy that enforces these constraints. Only expose this tool directly to trusted inputs.

---

### ProcessAgentTool

**Module:** `agentensemble-tools-process` |
**Package:** `net.agentensemble.tools.process`

Executes an external subprocess using the AgentEnsemble subprocess protocol.
See [Remote Tools](remote-tools.md) for the full protocol specification.

```java
import net.agentensemble.tools.process.ProcessAgentTool;

var sentiment = ProcessAgentTool.builder()
    .name("sentiment_analysis")
    .description("Analyzes text sentiment using Python NLTK")
    .command("python3", "/opt/tools/sentiment.py")
    .timeout(Duration.ofSeconds(30))
    .build();
```

---

### HttpAgentTool

**Module:** `agentensemble-tools-http` |
**Package:** `net.agentensemble.tools.http`

Wraps an HTTP REST endpoint as an agent tool.
See [Remote Tools](remote-tools.md) for full documentation.

```java
import net.agentensemble.tools.http.HttpAgentTool;

var classifier = HttpAgentTool.post(
    "text_classifier",
    "Classifies text into categories",
    "https://ml.example.com/classify");
```

---

## Using Multiple Tools

```java
var agent = Agent.builder()
    .role("Research Analyst")
    .goal("Research and analyze market trends")
    .tools(List.of(
        WebSearchTool.ofTavily(apiKey),
        new WebScraperTool(),
        new CalculatorTool(),
        new DateTimeTool(),
        FileWriteTool.of(Path.of("/workspace/output"))
    ))
    .llm(chatModel)
    .maxIterations(15)
    .build();
```
