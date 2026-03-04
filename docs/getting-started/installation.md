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

    // Optional: built-in tool library (calculator, datetime, file I/O, web search, etc.)
    implementation("net.agentensemble:agentensemble-tools:1.0.0")

    // Add the LangChain4j integration for your LLM provider:
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'net.agentensemble:agentensemble-core:1.0.0'

    // Optional: built-in tool library
    implementation 'net.agentensemble:agentensemble-tools:1.0.0'

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

    <!-- Optional: built-in tool library -->
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-tools</artifactId>
        <version>1.0.0</version>
    </dependency>

    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>1.11.0</version>
    </dependency>
</dependencies>
```

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

## Next Steps

- [Quickstart](quickstart.md) -- Build your first ensemble
- [Core Concepts](concepts.md) -- Understand the key abstractions
