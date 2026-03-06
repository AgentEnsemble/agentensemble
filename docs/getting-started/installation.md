# Installation

## Requirements

- Java 21 or later
- Gradle or Maven build tool
- A LangChain4j-supported LLM provider (OpenAI, Anthropic, Ollama, etc.)

---

## Add the Dependency

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("net.agentensemble:agentensemble-core:1.0.0")

    // Optional: add individual tools -- only include what you need.
    // Use the BOM (see below) to align all tool versions automatically.
    implementation("net.agentensemble:agentensemble-tools-calculator:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-datetime:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-web-search:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-web-scraper:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-json-parser:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-file-read:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-file-write:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-process:1.0.0")
    implementation("net.agentensemble:agentensemble-tools-http:1.0.0")

    // Optional: memory subsystem (MemoryStore SPI for task-scoped cross-execution memory;
    // also includes legacy short-term, long-term, and entity memory)
    implementation("net.agentensemble:agentensemble-memory:2.0.0")

    // Optional: human-in-the-loop review gates (ReviewHandler SPI, ConsoleReviewHandler,
    // and HumanInputTool integration)
    implementation("net.agentensemble:agentensemble-review:2.0.0")

    // Optional: Micrometer metrics integration
    implementation("net.agentensemble:agentensemble-metrics-micrometer:1.0.0")

    // Add the LangChain4j integration for your LLM provider:
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

### Using the BOM

The BOM (Bill of Materials) aligns all tool versions automatically:

```kotlin
dependencies {
    implementation(platform("net.agentensemble:agentensemble-tools-bom:1.0.0"))
    implementation("net.agentensemble:agentensemble-core:1.0.0")

    // No version needed for tools -- resolved from BOM
    implementation("net.agentensemble:agentensemble-tools-calculator")
    implementation("net.agentensemble:agentensemble-tools-web-search")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.agentensemble:agentensemble-core:1.0.0'
    implementation 'net.agentensemble:agentensemble-tools-calculator:1.0.0'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.11.0'
}
```

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-core</artifactId>
        <version>1.0.0</version>
    </dependency>

    <!-- Individual tool modules -->
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-tools-calculator</artifactId>
        <version>1.0.0</version>
    </dependency>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-tools-web-search</artifactId>
        <version>1.0.0</version>
    </dependency>

    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>1.11.0</version>
    </dependency>
</dependencies>
```

**With BOM (Maven):**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-tools-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-tools-calculator</artifactId>
        <!-- version from BOM -->
    </dependency>
</dependencies>
```

---

## Available Modules

| Module | Description |
|--------|-------------|
| `agentensemble-core` | Framework core -- required |
| `agentensemble-tools-calculator` | Arithmetic expression evaluator |
| `agentensemble-tools-datetime` | Date/time operations |
| `agentensemble-tools-json-parser` | JSON path extraction |
| `agentensemble-tools-file-read` | Sandboxed file reading |
| `agentensemble-tools-file-write` | Sandboxed file writing |
| `agentensemble-tools-web-search` | Web search (Tavily/SerpAPI) |
| `agentensemble-tools-web-scraper` | Web page text extraction |
| `agentensemble-tools-process` | Subprocess execution (cross-language) |
| `agentensemble-tools-http` | HTTP endpoint wrapping |
| `agentensemble-tools-bom` | Version alignment BOM |
| `agentensemble-memory` | Memory subsystem: task-scoped cross-execution memory with MemoryStore SPI; also includes legacy short-term, long-term, and entity memory |
| `agentensemble-review` | Human-in-the-loop review gates: ReviewHandler SPI, ConsoleReviewHandler, and HumanInputTool |
| `agentensemble-metrics-micrometer` | Micrometer metrics integration |

---

## Supported LLM Providers

AgentEnsemble uses the LangChain4j `ChatModel` interface and works with any provider that implements it:

| Provider | LangChain4j Artifact |
|---|---|
| OpenAI (GPT-4o, GPT-4o-mini, etc.) | `langchain4j-open-ai` |
| Anthropic (Claude 3.5, Claude 3 Haiku, etc.) | `langchain4j-anthropic` |
| Ollama (local models) | `langchain4j-ollama` |
| Azure OpenAI | `langchain4j-azure-open-ai` |
| Amazon Bedrock | `langchain4j-bedrock` |
| Google Vertex AI | `langchain4j-vertex-ai-gemini` |
| Mistral AI | `langchain4j-mistral-ai` |

For a full list, see the [LangChain4j documentation](https://docs.langchain4j.dev).

---

## Logging

AgentEnsemble uses SLF4J for logging. Add your preferred implementation:

```kotlin
// Logback (recommended)
implementation("ch.qos.logback:logback-classic:1.5.32")
```

See the [Logging guide](../guides/logging.md) for configuration details.

---

## Execution Graph Visualizer: agentensemble-viz

`agentensemble-viz` is a standalone developer tool for visualizing task dependency graphs
and execution timelines. It reads JSON files exported by `agentensemble-devtools` and
renders them in a local web UI.

**Homebrew (macOS and Linux):**

```bash
brew install agentensemble/tap/agentensemble-viz
agentensemble-viz ./traces/
```

This installs a self-contained native binary (no Node.js required). The formula is updated
automatically on every AgentEnsemble release.

**npx (no installation required):**

```bash
npx @agentensemble/viz ./traces/
```

**Global npm install:**

```bash
npm install -g @agentensemble/viz
agentensemble-viz ./traces/
```

See the [Visualization Guide](../guides/visualization.md) for the complete workflow including
how to export DAGs and execution traces from your Java application.

---

## Next Steps

- [Quickstart](quickstart.md) -- Build your first ensemble
- [Core Concepts](concepts.md) -- Understand the key abstractions
- [Built-in Tools](../guides/built-in-tools.md) -- Ready-to-use tool reference
- [Remote Tools](../guides/remote-tools.md) -- Cross-language tools with Python, Node.js, etc.
