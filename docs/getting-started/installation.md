# Installation

## Requirements

- Java 21 or later
- Gradle or Maven build tool
- A LangChain4j-supported LLM provider (OpenAI, Anthropic, Ollama, etc.)

---

## Add the Dependency

### Recommended: Use the agentensemble-bom

`agentensemble-bom` is a Bill of Materials that aligns every AgentEnsemble module to the
same version. Import it once and add only the modules you need -- no individual version
numbers required.

**Gradle (Kotlin DSL):**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // Import the BOM -- aligns all AgentEnsemble module versions
    implementation(platform("net.agentensemble:agentensemble-bom:2.0.0"))

    // Core framework -- always required
    implementation("net.agentensemble:agentensemble-core")

    // Optional: task-scoped cross-execution memory (MemoryStore SPI)
    implementation("net.agentensemble:agentensemble-memory")

    // Optional: human-in-the-loop review gates (ReviewHandler SPI, ConsoleReviewHandler)
    implementation("net.agentensemble:agentensemble-review")

    // Optional: built-in tools -- add only the ones you need
    implementation("net.agentensemble:agentensemble-tools-web-search")
    implementation("net.agentensemble:agentensemble-tools-web-scraper")
    implementation("net.agentensemble:agentensemble-tools-calculator")
    implementation("net.agentensemble:agentensemble-tools-datetime")
    // Other tools: agentensemble-tools-json-parser, agentensemble-tools-file-read,
    // agentensemble-tools-file-write, agentensemble-tools-process, agentensemble-tools-http

    // Optional: Micrometer metrics integration
    implementation("net.agentensemble:agentensemble-metrics-micrometer")

    // Add the LangChain4j integration for your LLM provider:
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

**Gradle (Groovy DSL):**

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation platform('net.agentensemble:agentensemble-bom:2.0.0')
    implementation 'net.agentensemble:agentensemble-core'
    implementation 'net.agentensemble:agentensemble-memory'
    implementation 'net.agentensemble:agentensemble-review'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.11.0'
}
```

**Maven:**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-bom</artifactId>
            <version>2.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
<dependencies>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-core</artifactId>
        <!-- version from BOM -->
    </dependency>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-memory</artifactId>
        <!-- version from BOM -->
    </dependency>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-review</artifactId>
        <!-- version from BOM -->
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>1.11.0</version>
    </dependency>
</dependencies>
```

### Minimal: Core Only

If you need only the framework core (no memory, no review, no built-in tools):

```kotlin
dependencies {
    implementation("net.agentensemble:agentensemble-core:2.0.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

### Tools-Only BOM

If you want to align just the built-in tool versions without importing the full BOM:

```kotlin
dependencies {
    implementation(platform("net.agentensemble:agentensemble-tools-bom:2.0.0"))
    implementation("net.agentensemble:agentensemble-core:2.0.0")

    // No version needed for tools -- resolved from tools BOM
    implementation("net.agentensemble:agentensemble-tools-calculator")
    implementation("net.agentensemble:agentensemble-tools-web-search")
}
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
| `agentensemble-bom` | Top-level BOM -- aligns all module versions in one import |

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
