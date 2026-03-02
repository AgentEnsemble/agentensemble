# Tech Context

## Technologies

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 | Language target |
| Gradle | Latest (Kotlin DSL) | Build system |
| LangChain4j | Latest stable | LLM integration (core dependency) |
| Lombok | Latest | @Value, @Builder, boilerplate reduction |
| Jackson | Latest | JSON serialization for tool I/O |
| SLF4J | Latest | Logging facade |
| JUnit 5 | Latest | Test framework |
| AssertJ | Latest | Fluent assertions |
| Mockito | Latest | Mocking |

## Development Setup

- Gradle wrapper (`./gradlew`)
- Version catalog at `gradle/libs.versions.toml`
- Java 21 required
- No external services needed for development (all LLM calls mocked in tests)

## Technical Constraints

- Java 21 minimum (uses records, sealed classes, virtual threads support)
- No SLF4J implementation in core (users bring their own)
- LangChain4j's `ChatLanguageModel` is the LLM abstraction (no custom LLM interface)
- All domain objects must be immutable
- No singletons or static mutable state

## Dependencies

### agentensemble-core
- `api`: langchain4j-core (exposed to users)
- `implementation`: jackson-databind, slf4j-api
- `compileOnly` + `annotationProcessor`: lombok
- `testImplementation`: junit-jupiter, assertj-core, mockito-core, slf4j-simple

### agentensemble-examples
- `implementation`: agentensemble-core, langchain4j-open-ai, logback-classic

## Repository

- GitHub: github.com/AgentEnsemble/agentensemble
- License: MIT
- Branch strategy: feature branches per issue, merged to main
