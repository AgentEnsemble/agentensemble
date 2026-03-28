# MCP Bridge

The `agentensemble-mcp` module bridges the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/)
ecosystem into AgentEnsemble. It adapts MCP server tools to the `AgentTool` interface so
they can be used alongside Java-native tools in any agent's tool list.

---

## Installation

=== "Gradle"

    ```kotlin
    implementation("net.agentensemble:agentensemble-mcp:VERSION")
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>net.agentensemble</groupId>
      <artifactId>agentensemble-mcp</artifactId>
      <version>VERSION</version>
    </dependency>
    ```

---

## Connecting to Any MCP Server

Use `McpToolFactory.fromServer()` to connect to any MCP-compatible server and obtain
its tools as `AgentTool` instances:

```java
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import net.agentensemble.mcp.McpToolFactory;

// Create a transport for a stdio-based MCP server
StdioMcpTransport transport = new StdioMcpTransport.Builder()
    .command(List.of("npx", "@modelcontextprotocol/server-filesystem", "/workspace"))
    .build();

// Get all tools from the server
List<AgentTool> tools = McpToolFactory.fromServer(transport);

// Or filter to specific tools by name
List<AgentTool> filtered = McpToolFactory.fromServer(transport, "read_file", "write_file");
```

Each tool's parameter schema is passed through directly from the MCP server to the LLM --
the LLM sees properly typed, named parameters just as with `TypedAgentTool` records.

---

## Convenience: Filesystem and Git Servers

`McpToolFactory` provides convenience methods for the well-known MCP reference servers:

```java
import net.agentensemble.mcp.McpToolFactory;
import net.agentensemble.mcp.McpServerLifecycle;

// Filesystem server: read, write, search, list files
try (McpServerLifecycle fs = McpToolFactory.filesystem(Path.of("/workspace"))) {
    fs.start();
    List<AgentTool> fsTools = fs.tools();

    var agent = Agent.builder()
        .role("File Manager")
        .tools(fsTools)
        .llm(chatModel)
        .build();
}

// Git server: status, diff, log, commit, branch
try (McpServerLifecycle git = McpToolFactory.git(Path.of("/repo"))) {
    git.start();
    List<AgentTool> gitTools = git.tools();
    // ...
}
```

These methods require `npx` (Node.js) to be available on the system PATH. They spawn
the MCP reference servers as subprocesses:

- `filesystem(Path)` runs `npx @modelcontextprotocol/server-filesystem <dir>`
- `git(Path)` runs `npx @modelcontextprotocol/server-git --repository <dir>`

---

## Lifecycle Management

`McpServerLifecycle` manages the MCP server subprocess lifecycle. It implements
`AutoCloseable` for use with try-with-resources:

```java
try (McpServerLifecycle server = McpToolFactory.filesystem(projectDir)) {
    server.start();              // Spawn subprocess, initialize MCP protocol
    List<AgentTool> tools = server.tools();  // List and cache tools
    boolean alive = server.isAlive();        // Check health

    // ... use tools ...

}  // Automatically closes the server on exit
```

**State machine:**

```
CREATED  --start()-->  STARTED  --close()-->  CLOSED
                                       ^
CREATED  --close()---->  CLOSED -------+
```

- `start()` spawns the server subprocess and performs a health check
- `tools()` lists available tools (cached after first call)
- `close()` shuts down the server; idempotent
- `isAlive()` returns true when started and not yet closed

---

## Mixing MCP Tools with Java Tools

MCP tools and Java tools produce standard `AgentTool` instances. They can be freely
mixed in a single agent's tool list:

```java
// MCP tools for filesystem operations
try (McpServerLifecycle fs = McpToolFactory.filesystem(workDir)) {
    fs.start();

    var agent = Agent.builder()
        .role("Developer")
        .tools(List.of(
            // MCP tools
            fs.tools().get(0),   // read_file
            fs.tools().get(1),   // write_file
            // Java tools
            new CalculatorTool(),
            WebSearchTool.ofTavily(apiKey)
        ))
        .llm(chatModel)
        .build();
}
```

---

## Complete Example

```java
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.mcp.McpToolFactory;
import net.agentensemble.mcp.McpServerLifecycle;

// Start MCP filesystem server scoped to the project directory
try (McpServerLifecycle fs = McpToolFactory.filesystem(Path.of("/my/project"))) {
    fs.start();

    Agent coder = Agent.builder()
        .role("Software Engineer")
        .goal("Read code and answer questions about it")
        .tools(fs.tools())
        .llm(chatModel)
        .maxIterations(20)
        .build();

    Task task = Task.builder()
        .description("Find the main entry point of the application and explain what it does")
        .build();

    var result = Ensemble.builder()
        .agent(coder)
        .task(task)
        .build()
        .run();

    System.out.println(result.output());
}
```
