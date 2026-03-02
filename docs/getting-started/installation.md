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
    maven {
        url = uri("https://maven.pkg.github.com/AgentEnsemble/agentensemble")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("net.agentensemble:agentensemble-core:0.4.0")

    // Add the LangChain4j integration for your LLM provider:
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

### Gradle (Groovy DSL)

```groovy
repositories {
    maven {
        url 'https://maven.pkg.github.com/AgentEnsemble/agentensemble'
        credentials {
            username System.getenv('GITHUB_ACTOR')
            password System.getenv('GITHUB_TOKEN')
        }
    }
}

dependencies {
    implementation 'net.agentensemble:agentensemble-core:0.4.0'
    implementation 'dev.langchain4j:langchain4j-open-ai:1.11.0'
}
```

### Maven

```xml
<repositories>
    <repository>
        <id>agentensemble-github</id>
        <url>https://maven.pkg.github.com/AgentEnsemble/agentensemble</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>net.agentensemble</groupId>
        <artifactId>agentensemble-core</artifactId>
        <version>0.4.0</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>1.11.0</version>
    </dependency>
</dependencies>
```

---

## GitHub Packages Authentication

AgentEnsemble is published to GitHub Packages. Authentication is required to download packages from GitHub Packages, even for public repositories.

**Set environment variables:**

```bash
export GITHUB_ACTOR=your-github-username
export GITHUB_TOKEN=your-personal-access-token
```

Your personal access token needs the `read:packages` scope. Create one at: **GitHub Settings > Developer Settings > Personal Access Tokens**.

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
implementation("ch.qos.logback:logback-classic:1.5.12")
```

See the [Logging guide](../guides/logging.md) for configuration details.

---

## Next Steps

- [Quickstart](quickstart.md) -- Build your first ensemble
- [Core Concepts](concepts.md) -- Understand the key abstractions
