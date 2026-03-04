# Built-in Tools

The `agentensemble-tools` module provides ready-to-use implementations of common tools that agents
frequently need. Each tool implements the `AgentTool` interface and can be registered on any agent.

## Adding the Dependency

`agentensemble-tools` is a separate artifact. Add it alongside `agentensemble-core`:

=== "Gradle (Kotlin DSL)"

    ```kotlin
    dependencies {
        implementation("net.agentensemble:agentensemble-core:1.0.0")
        implementation("net.agentensemble:agentensemble-tools:1.0.0")
    }
    ```

=== "Maven"

    ```xml
    <dependencies>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-core</artifactId>
            <version>1.0.0</version>
        </dependency>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-tools</artifactId>
            <version>1.0.0</version>
        </dependency>
    </dependencies>
    ```

---

## CalculatorTool

Evaluates arithmetic expressions. Supports `+`, `-`, `*`, `/`, `%` (modulo), `^` (power), and
parentheses. Operator precedence follows standard math conventions.

**Input:** A math expression string.

**Output:** The numeric result as a string.

```java
var calculator = new CalculatorTool();

// Register on an agent
var analyst = Agent.builder()
    .role("Financial Analyst")
    .goal("Perform financial calculations")
    .llm(model)
    .tool(calculator)
    .build();
```

**Supported operations:**

| Expression          | Result |
|---------------------|--------|
| `2 + 3 * 4`         | `14`   |
| `(2 + 3) * 4`       | `20`   |
| `17 % 5`            | `2`    |
| `2 ^ 10`            | `1024` |
| `1.5 + 2.5`         | `4`    |

---

## DateTimeTool

Provides current date/time, timezone conversion, and date arithmetic using `java.time`.

**Input:** A command string (see supported commands below).

**Output:** A formatted date or datetime string.

```java
var dateTime = new DateTimeTool();

var scheduler = Agent.builder()
    .role("Scheduler")
    .goal("Manage dates and deadlines")
    .llm(model)
    .tool(dateTime)
    .build();
```

**Supported commands:**

| Command                                               | Example Output                         |
|-------------------------------------------------------|----------------------------------------|
| `now`                                                 | `2024-03-15T12:30:00 UTC`              |
| `now in America/New_York`                             | `2024-03-15T08:30:00 America/New_York` |
| `today`                                               | `2024-03-15`                           |
| `today in Pacific/Auckland`                           | `2024-03-16`                           |
| `2024-03-15 + 5 days`                                 | `2024-03-20`                           |
| `2024-01-31 + 1 month`                                | `2024-02-29`                           |
| `2024-03-15T10:00:00Z + 3 hours`                      | `2024-03-15T13:00:00Z`                 |
| `convert 2024-03-15T12:00:00Z from UTC to America/Chicago` | `2024-03-15T07:00:00 America/Chicago` |

Supported units for arithmetic: `days`, `weeks`, `months`, `years`, `hours`, `minutes`, `seconds`.
Timezone IDs use IANA format (e.g., `America/New_York`, `Europe/London`, `UTC`).

---

## FileReadTool

Reads file contents from a sandboxed base directory. Path traversal attacks (via `../`) are
rejected before any filesystem access.

**Input:** A file path relative to the sandbox directory.

**Output:** The file contents as a string.

```java
// Sandbox to a specific directory
var fileReader = FileReadTool.of(Path.of("/workspace/data"));

var researcher = Agent.builder()
    .role("Research Analyst")
    .goal("Analyze documents")
    .llm(model)
    .tool(fileReader)
    .build();
```

**Sandboxing:** Any path that resolves outside the base directory -- including `../secret.txt` or
an absolute path like `/etc/passwd` -- is rejected with an access-denied failure result.

---

## FileWriteTool

Writes content to a file within a sandboxed base directory. Parent directories are created
automatically. Uses the same path-traversal protection as `FileReadTool`.

**Input:** A JSON object with `path` and `content` fields.

**Output:** A success message including the file path written.

```java
var fileWriter = FileWriteTool.of(Path.of("/workspace/output"));

var writer = Agent.builder()
    .role("Report Writer")
    .goal("Produce written outputs")
    .llm(model)
    .tool(fileWriter)
    .build();
```

**Input format:**

```json
{"path": "reports/analysis.txt", "content": "The analysis shows..."}
```

Subdirectories in the path are created automatically if they do not exist.

---

## WebSearchTool

Performs a web search via a configurable provider and returns formatted results.

**Input:** A search query string.

**Output:** Formatted search results as plain text.

```java
// Tavily (recommended for AI agents)
var webSearch = WebSearchTool.ofTavily(System.getenv("TAVILY_API_KEY"));

// SerpAPI (Google)
var webSearch = WebSearchTool.ofSerpApi(System.getenv("SERPAPI_API_KEY"));

// Custom provider
var webSearch = WebSearchTool.of(query -> {
    // your HTTP logic
    return formattedResults;
});

var researcher = Agent.builder()
    .role("Research Analyst")
    .goal("Find and summarize online information")
    .llm(model)
    .tool(webSearch)
    .build();
```

**Providers:**

| Factory Method                          | Provider          |
|-----------------------------------------|-------------------|
| `WebSearchTool.ofTavily(apiKey)`        | Tavily Search API |
| `WebSearchTool.ofSerpApi(apiKey)`       | SerpAPI (Google)  |
| `WebSearchTool.of(WebSearchProvider)`   | Custom            |

The `WebSearchProvider` interface is a single-method functional interface, making lambda
implementations straightforward.

---

## WebScraperTool

Fetches a web page and extracts its readable text content using Jsoup. Strips HTML tags, scripts,
navigation, headers, and footers. Truncates output to a configurable character limit to avoid
overwhelming the LLM context window.

**Input:** A URL string.

**Output:** Plain-text content extracted from the page.

```java
// Default (5000 chars, 10s timeout)
var scraper = new WebScraperTool();

// Custom max content length
var scraper = WebScraperTool.withMaxContentLength(3000);

var analyst = Agent.builder()
    .role("Web Analyst")
    .goal("Extract information from web pages")
    .llm(model)
    .tool(scraper)
    .build();
```

Output exceeding the maximum length is truncated with a `[truncated at N chars]` notice appended.

---

## JsonParserTool

Extracts values from JSON using dot-notation path expressions with optional array indexing.

**Input:** Two sections separated by a newline. The first line is the path; the remaining lines
are the JSON to parse.

**Output:** The extracted value as a string. Objects and arrays are returned as compact JSON.

```java
var jsonParser = new JsonParserTool();

var extractor = Agent.builder()
    .role("Data Extractor")
    .goal("Parse and extract structured data")
    .llm(model)
    .tool(jsonParser)
    .build();
```

**Path syntax:**

| Path                     | JSON                                            | Output  |
|--------------------------|-------------------------------------------------|---------|
| `name`                   | `{"name": "Alice"}`                             | `Alice` |
| `user.address.city`      | `{"user": {"address": {"city": "Denver"}}}`     | `Denver`|
| `items[0]`               | `{"items": ["alpha", "beta"]}`                  | `alpha` |
| `users[1].name`          | `{"users": [{"name": "A"}, {"name": "B"}]}`     | `B`     |

**Input example:**

```
user.address.city
{"user": {"address": {"city": "Denver", "zip": "80203"}}}
```

---

## Using Multiple Tools Together

Agents can be configured with any combination of built-in tools:

```java
var calculator = new CalculatorTool();
var dateTime = new DateTimeTool();
var fileReader = FileReadTool.of(Path.of("/workspace/data"));
var fileWriter = FileWriteTool.of(Path.of("/workspace/output"));
var webSearch = WebSearchTool.ofTavily(System.getenv("TAVILY_API_KEY"));
var scraper = new WebScraperTool();
var jsonParser = new JsonParserTool();

var universalAgent = Agent.builder()
    .role("Research and Analysis Specialist")
    .goal("Research topics using web sources, analyze data, and produce written reports")
    .llm(model)
    .tool(webSearch)
    .tool(scraper)
    .tool(calculator)
    .tool(dateTime)
    .tool(fileReader)
    .tool(fileWriter)
    .tool(jsonParser)
    .build();
```

See the [Tools guide](tools.md) for information on combining built-in tools with custom tools.
