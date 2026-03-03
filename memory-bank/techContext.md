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
| Spotless | 7.0.2 | Code formatting (palantir-java-format 2.47.0) |
| Error Prone | 4.2.0 (plugin), 2.36.0 (core) | Compile-time bug detection |
| JaCoCo | Gradle built-in | Test coverage reporting and enforcement |
| Codecov | codecov-action@v5 | Coverage tracking and PR comments (CI) |

## Development Setup

- Gradle wrapper (`./gradlew`)
- Version catalog at `gradle/libs.versions.toml`
- Java 21 required
- No external services needed for development (all LLM calls mocked in tests)
- Git hooks: run `./gradlew setupGitHooks` after cloning to enable the pre-commit hook
- Pre-commit hook: `.githooks/pre-commit` -- runs `spotlessApply` and re-stages reformatted files automatically

## Code Quality

- **Formatting**: `./gradlew spotlessApply` -- auto-formats all Java and Kotlin Gradle files
- **Format check**: `./gradlew spotlessCheck` -- run by `check` task; fails CI on violations
- **Bug detection**: Error Prone runs at compile time; surfaced as warnings (not errors) by default
- **Coverage report**: `./gradlew jacocoTestReport` -- HTML at `build/reports/jacoco/test/index.html`, XML for Codecov
- **Coverage gate**: `jacocoTestCoverageVerification` wired into `check` for `agentensemble-core`:
  - LINE minimum: 90% (current: ~94%)
  - BRANCH minimum: 75% (current: ~81%)
- **Codecov**: coverage uploaded on every CI run; configured in `codecov.yml`

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
