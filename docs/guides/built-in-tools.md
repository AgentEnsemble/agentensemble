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

#### Approval Gate

Require human approval before any file write:

```java
FileWriteTool writeTool = FileWriteTool.builder(Path.of("/workspace/output"))
    .requireApproval(true)
    .build();
```

The reviewer sees the target path and a preview of the content (up to 200 characters).
On `Edit`, the reviewer's text replaces the original file content. On `ExitEarly`, no
file is written and the tool returns a failure result.

Requires `agentensemble-review` on the runtime classpath and a `ReviewHandler` configured
on the ensemble (see [Tool-Level Approval Gates](review.md#tool-level-approval-gates)).

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

#### Approval Gate

Require human approval before the subprocess is started:

```java
ProcessAgentTool shellTool = ProcessAgentTool.builder()
    .name("shell")
    .description("Executes shell commands")
    .command("sh", "-c")
    .requireApproval(true)   // reviewer must approve before the process starts
    .build();
```

The reviewer sees the command and a preview of the input being sent to the process.
On `Edit`, the reviewer's text replaces the original input (sent to the subprocess's stdin).
On `ExitEarly`, the process is never started and the tool returns a failure result.

Requires `agentensemble-review` on the runtime classpath and a `ReviewHandler` configured
on the ensemble (see [Tool-Level Approval Gates](review.md#tool-level-approval-gates)).

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

#### Approval Gate

Require human approval before any HTTP request is sent. Useful for destructive operations
(DELETE, PUT) or calls to production APIs:

```java
HttpAgentTool deleteApi = HttpAgentTool.builder()
    .name("delete_records")
    .description("Permanently deletes records from the production database")
    .url("https://api.production.example.com/records")
    .method("DELETE")
    .requireApproval(true)   // reviewer must approve before the request is sent
    .build();
```

The reviewer sees the HTTP method, URL, and a preview of the request body.
On `Edit`, the reviewer's text replaces the original request body.
On `ExitEarly`, no request is sent and the tool returns a failure result.

Requires `agentensemble-review` on the runtime classpath and a `ReviewHandler` configured
on the ensemble (see [Tool-Level Approval Gates](review.md#tool-level-approval-gates)).

---

## MCP Bridge

**Module:** `agentensemble-mcp` |
**Package:** `net.agentensemble.mcp`

Bridges the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) ecosystem
into AgentEnsemble. Connects to any MCP server and exposes its tools as standard
`AgentTool` instances.

```java
import net.agentensemble.mcp.McpToolFactory;
import net.agentensemble.mcp.McpServerLifecycle;

// Start the MCP filesystem server
try (McpServerLifecycle fs = McpToolFactory.filesystem(Path.of("/workspace"))) {
    fs.start();
    List<AgentTool> tools = fs.tools();
    // Use tools with any agent
}

// Or connect to any MCP server
List<AgentTool> tools = McpToolFactory.fromServer(transport);
```

**Key classes:**

| Class | Description |
|-------|-------------|
| `McpAgentTool` | Wraps a single MCP tool as an `AgentTool` |
| `McpToolFactory` | Factory methods to create tools from MCP servers |
| `McpServerLifecycle` | Manages MCP server subprocess lifecycle |

**Full documentation:** [MCP Bridge Guide](mcp.md)

---

## Coding Tools

The `agentensemble-tools-coding` module provides 7 coding-specific tools for agents
that need to read, search, edit, and build code. All tools are sandboxed to a base
directory and support optional approval gates for destructive operations.

### Installation

=== "Gradle"

    ```kotlin
    implementation("net.agentensemble:agentensemble-tools-coding:VERSION")
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>net.agentensemble</groupId>
      <artifactId>agentensemble-tools-coding</artifactId>
      <version>VERSION</version>
    </dependency>
    ```

---

### GlobTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Finds files matching a glob pattern within a sandboxed workspace. Uses
`java.nio.file.PathMatcher` with `Files.walkFileTree`. Results are sorted
alphabetically and capped at 200 entries.

```java
import net.agentensemble.tools.coding.GlobTool;

var glob = GlobTool.of(Path.of("/workspace/project"));
```

**Input examples:**
- `{"pattern": "**/*.java"}` -- find all Java files recursively
- `{"pattern": "**/*.ts", "path": "src"}` -- find TypeScript files under `src/`

---

### CodeSearchTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Searches code content using regex patterns. Detects `rg` > `grep` > pure-Java
fallback at construction time. Returns matches formatted as `file:line:content`,
capped at 100 results.

```java
import net.agentensemble.tools.coding.CodeSearchTool;

var search = CodeSearchTool.of(Path.of("/workspace/project"));
```

**Input examples:**
- `{"pattern": "class\\s+\\w+Service"}` -- find service classes
- `{"pattern": "TODO", "glob": "*.java", "ignoreCase": true}` -- find TODOs in Java files
- `{"pattern": "import", "contextLines": 2, "path": "src/main"}` -- search with context

---

### CodeEditTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Performs surgical code edits with three modes: `replace_lines` (line range replacement),
`find_replace` (text/regex find and replace), and `write` (full file overwrite).

```java
import net.agentensemble.tools.coding.CodeEditTool;

// Without approval gate
var edit = CodeEditTool.of(Path.of("/workspace/project"));

// With approval gate
var edit = CodeEditTool.builder(Path.of("/workspace/project"))
    .requireApproval(true)
    .build();
```

**Input examples:**
- `{"path": "Foo.java", "command": "replace_lines", "startLine": 5, "endLine": 7, "content": "new code"}` -- replace lines 5-7
- `{"path": "Foo.java", "command": "find_replace", "find": "oldMethod", "content": "newMethod"}` -- literal find/replace
- `{"path": "Foo.java", "command": "find_replace", "find": "log\\.\\w+", "content": "logger.info", "regex": true}` -- regex find/replace
- `{"path": "new.txt", "command": "write", "content": "file content"}` -- write new file

#### Approval Gate

When `requireApproval(true)` is set, the reviewer sees the file path, edit mode,
and a content preview. On `ExitEarly`, no edit is made.

---

### ShellTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Executes shell commands within the workspace. Uses `sh -c` on Unix, `cmd /c` on
Windows. Output is truncated at a configurable limit (default 10,000 characters).

```java
import net.agentensemble.tools.coding.ShellTool;

var shell = ShellTool.builder(Path.of("/workspace/project"))
    .requireApproval(true)          // default: true
    .timeout(Duration.ofSeconds(60))
    .maxOutputLength(10_000)
    .build();
```

**Input examples:**
- `{"command": "ls -la src/main/java"}` -- list files
- `{"command": "wc -l **/*.java", "workingDir": "src"}` -- count lines in subdirectory
- `{"command": "curl -s https://example.com", "timeoutSeconds": 10}` -- with custom timeout

#### Approval Gate

Shell execution requires approval by default. The reviewer sees the command and
working directory.

---

### GitTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Executes git operations in the workspace repository. Supports commands: `status`,
`diff`, `log`, `commit`, `add`, `branch`, `stash`, `checkout`, `show`, `tag`,
`merge`, `fetch`, `pull`, `push`, `reset`. Destructive operations (`push`,
`reset --hard`, `rebase`, `clean`, `force-push`) require approval when enabled.

```java
import net.agentensemble.tools.coding.GitTool;

var git = GitTool.builder(Path.of("/workspace/repo"))
    .requireApproval(true)  // for destructive ops only
    .build();
```

**Input examples:**
- `{"command": "status"}` -- check working tree status
- `{"command": "diff"}` -- show unstaged changes
- `{"command": "log", "args": "--oneline -10"}` -- recent commits
- `{"command": "add", "args": "src/main/java/Foo.java"}` -- stage a file
- `{"command": "commit", "message": "Fix null check in UserService"}` -- commit
- `{"command": "branch", "args": "-a"}` -- list branches

#### Approval Gate

When `requireApproval(true)` is set, only destructive operations trigger the
approval gate. Safe operations (status, diff, log, add, commit) proceed without
approval.

---

### BuildRunnerTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Runs build commands and parses the output into structured results. Heuristically
extracts errors and warnings, producing both human-readable output and a structured
JSON payload: `{"success": true, "errors": [], "warnings": []}`.

```java
import net.agentensemble.tools.coding.BuildRunnerTool;

var build = BuildRunnerTool.of(Path.of("/workspace/project"));

// With custom timeout
var build = BuildRunnerTool.builder(Path.of("/workspace/project"))
    .timeout(Duration.ofSeconds(300))
    .build();
```

**Input examples:**
- `{"command": "gradle build"}` -- run Gradle build
- `{"command": "mvn compile", "workingDir": "submodule"}` -- Maven build in subdirectory
- `{"command": "npm run build"}` -- npm build

---

### TestRunnerTool

**Module:** `agentensemble-tools-coding` |
**Package:** `net.agentensemble.tools.coding`

Runs tests and parses the results into structured output. Recognizes JUnit/Gradle,
Maven Surefire, and npm/Jest output patterns. Falls back to exit-code-based
success/failure when no pattern matches. Returns a structured `TestResult` with
pass/fail/skip counts and failure details.

```java
import net.agentensemble.tools.coding.TestRunnerTool;

var test = TestRunnerTool.of(Path.of("/workspace/project"));

// With custom timeout
var test = TestRunnerTool.builder(Path.of("/workspace/project"))
    .timeout(Duration.ofSeconds(600))
    .build();
```

**Input examples:**
- `{"command": "gradle test"}` -- run all tests
- `{"command": "gradle test", "testFilter": "--tests 'com.example.FooTest'"}` -- run specific test
- `{"command": "mvn test", "workingDir": "submodule"}` -- Maven tests in subdirectory
- `{"command": "npm test"}` -- npm tests

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

---

## Pipelining Built-in Tools

Use `ToolPipeline` to chain built-in tools together so they execute sequentially inside a
single LLM tool call, with no LLM round-trips between steps.

```java
import net.agentensemble.tool.ToolPipeline;
import net.agentensemble.tool.PipelineErrorStrategy;

// Chain JsonParserTool and CalculatorTool: extract a number, then apply a formula
ToolPipeline extractAndCalculate = ToolPipeline.builder()
    .name("extract_and_calculate")
    .description("Extracts a numeric field from JSON and applies a 10% markup formula. "
        + "Input: path on first line, JSON on remaining lines.")
    .step(new JsonParserTool())
    .adapter(result -> result.getOutput() + " * 1.1")  // reshape for CalculatorTool
    .step(new CalculatorTool())
    .build();

// Chain search, extract, and save -- three steps, one LLM tool call
ToolPipeline searchAndSave = ToolPipeline.builder()
    .name("search_and_save")
    .description("Searches the web, extracts the first result title, and saves it.")
    .step(WebSearchTool.ofTavily(apiKey))
    .adapter(result -> "results[0].title\n" + result.getOutput())
    .step(new JsonParserTool())
    .step(FileWriteTool.of(Path.of("/workspace/output")))
    .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
    .build();

// Register on a task -- the pipeline appears as a single tool to the LLM
var task = Task.builder()
    .description("Research AI trends and save the top result")
    .tools(List.of(searchAndSave))
    .build();
```

**Full documentation:** [Tool Pipeline Guide](tool-pipeline.md)
