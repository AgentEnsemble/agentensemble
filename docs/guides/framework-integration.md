# Framework Integration

AgentEnsemble is a plain Java library with no framework dependencies. It works in any
Java 21+ environment -- Spring Boot, Micronaut, Quarkus, Jakarta EE, AWS Lambda, or a
plain `main()` method.

This guide shows how to wire AgentEnsemble into popular frameworks using their native
dependency injection mechanisms.

!!! tip "No magic required"
    The AgentEnsemble builder API is intentionally explicit. Framework integration is
    simply a matter of wrapping those same builder calls in the DI mechanism your
    framework uses. Nothing in the library changes.

---

## Spring Boot

### Dependencies

Add `agentensemble-core` and a LangChain4j chat-model provider. The LangChain4j Spring
Boot starters handle `ChatLanguageModel` bean creation from `application.properties`
automatically -- AgentEnsemble does not duplicate that responsibility.

=== "Gradle (Kotlin DSL)"

    ```kotlin title="build.gradle.kts"
    dependencies {
        implementation("net.agentensemble:agentensemble-core:1.3.0")
        implementation("dev.langchain4j:langchain4j-spring-boot-starter:1.11.0")
        implementation("dev.langchain4j:langchain4j-open-ai-spring-boot-starter:1.11.0")

        // Optional: Micrometer metrics exposed via Spring Boot Actuator
        implementation("net.agentensemble:agentensemble-metrics-micrometer:1.3.0")
    }
    ```

=== "Maven"

    ```xml title="pom.xml"
    <dependencies>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-core</artifactId>
            <version>1.3.0</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-spring-boot-starter</artifactId>
            <version>1.11.0</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
            <version>1.11.0</version>
        </dependency>

        <!-- Optional -->
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-metrics-micrometer</artifactId>
            <version>1.3.0</version>
        </dependency>
    </dependencies>
    ```

!!! note "Latest versions"
    Check [Maven Central](https://central.sonatype.com/search?q=net.agentensemble) for
    the current AgentEnsemble release. Refer to the
    [LangChain4j documentation](https://docs.langchain4j.dev/integrations/language-models/open-ai)
    for exact starter artifact IDs and `application.properties` key names, as these
    may change between LangChain4j major versions.

### LLM Configuration

Configure your LLM provider in `application.properties`. These properties are handled
by the LangChain4j starter -- AgentEnsemble reads the `ChatLanguageModel` bean that
the starter creates.

```properties title="application.properties"
langchain4j.open-ai.chat-model.api-key=${OPENAI_API_KEY}
langchain4j.open-ai.chat-model.model-name=gpt-4o
```

### Wiring the Ensemble

Create a `@Configuration` class. Spring injects the `ChatLanguageModel` bean and any
`EnsembleListener` or `ToolMetrics` beans you have defined.

```java title="AgentEnsembleConfig.java"
@Configuration
public class AgentEnsembleConfig {

    @Bean
    public Agent researcher() {
        return Agent.builder()
                .role("Research Analyst")
                .goal("Find accurate, up-to-date information on the given topic")
                .backstory("You are a meticulous researcher with a talent for "
                        + "finding relevant information quickly.")
                .build();
    }

    @Bean
    public Agent writer() {
        return Agent.builder()
                .role("Content Writer")
                .goal("Write clear, engaging content based on research findings")
                .backstory("You are an experienced writer who excels at making "
                        + "complex topics accessible.")
                .build();
    }

    @Bean
    public Ensemble ensemble(
            ChatLanguageModel chatModel,
            Agent researcher,
            Agent writer,
            List<EnsembleListener> listeners,
            Optional<ToolMetrics> toolMetrics) {

        Ensemble.Builder builder = Ensemble.builder()
                .chatModel(chatModel)
                .agents(researcher, writer);

        listeners.forEach(builder::listener);
        toolMetrics.ifPresent(builder::toolMetrics);

        return builder.build();
    }
}
```

### Listeners as Beans

Any `EnsembleListener` declared as a `@Bean` or `@Component` is automatically
collected by the ensemble configuration above via `List<EnsembleListener>` injection.

```java title="LoggingListener.java"
@Component
public class LoggingListener implements EnsembleListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingListener.class);

    @Override
    public void onTaskStart(TaskStartEvent event) {
        log.info("Task started: {}", event.task().description());
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        log.info("Task completed in {}ms", event.metrics().durationMs());
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        log.error("Task failed: {}", event.cause().getMessage());
    }
}
```

### Micrometer Metrics

If you use Spring Boot Actuator with Micrometer, declare a `ToolMetrics` bean. Metrics
are then automatically available at `/actuator/metrics`.

```java title="MetricsConfig.java"
@Configuration
public class MetricsConfig {

    @Bean
    public ToolMetrics toolMetrics(MeterRegistry registry) {
        return new MicrometerToolMetrics(registry);
    }
}
```

### Using the Ensemble

Inject the `Ensemble` bean wherever you need it. Build tasks at the call site where
you have the runtime inputs.

```java title="ResearchService.java"
@Service
public class ResearchService {

    private final Ensemble ensemble;
    private final Agent researcher;

    public ResearchService(Ensemble ensemble, Agent researcher) {
        this.ensemble = ensemble;
        this.researcher = researcher;
    }

    public String research(String topic) {
        Task task = Task.builder()
                .description("Research and summarise: " + topic)
                .expectedOutput("A concise summary with key findings, 3-5 paragraphs")
                .agent(researcher)
                .build();

        EnsembleOutput output = ensemble.run(task);
        return output.finalOutput();
    }
}
```

---

## Micronaut

### Dependencies

Micronaut does not have a LangChain4j integration module, so create the
`ChatLanguageModel` bean directly.

=== "Gradle (Kotlin DSL)"

    ```kotlin title="build.gradle.kts"
    dependencies {
        implementation("net.agentensemble:agentensemble-core:1.3.0")
        implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")

        // Optional: Micrometer (Micronaut ships with native Micrometer support)
        implementation("net.agentensemble:agentensemble-metrics-micrometer:1.3.0")
    }
    ```

=== "Maven"

    ```xml title="pom.xml"
    <dependencies>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-core</artifactId>
            <version>1.3.0</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai</artifactId>
            <version>1.11.0</version>
        </dependency>
    </dependencies>
    ```

### Configuration

```yaml title="src/main/resources/application.yml"
agentensemble:
  openai:
    api-key: ${OPENAI_API_KEY}
    model-name: gpt-4o
```

### Wiring the Ensemble

Use a `@Factory` class. Micronaut injects all `EnsembleListener` beans automatically
via `List<EnsembleListener>` -- no extra wiring needed.

```java title="AgentEnsembleFactory.java"
@Factory
public class AgentEnsembleFactory {

    @Singleton
    public ChatLanguageModel chatModel(
            @Value("${agentensemble.openai.api-key}") String apiKey,
            @Value("${agentensemble.openai.model-name}") String modelName) {

        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Singleton
    public Agent researcher() {
        return Agent.builder()
                .role("Research Analyst")
                .goal("Find accurate, up-to-date information on the given topic")
                .backstory("You are a meticulous researcher.")
                .build();
    }

    @Singleton
    public Agent writer() {
        return Agent.builder()
                .role("Content Writer")
                .goal("Write clear, engaging content based on research findings")
                .backstory("You are an experienced writer.")
                .build();
    }

    @Singleton
    public Ensemble ensemble(
            ChatLanguageModel chatModel,
            Agent researcher,
            Agent writer,
            List<EnsembleListener> listeners) {

        Ensemble.Builder builder = Ensemble.builder()
                .chatModel(chatModel)
                .agents(researcher, writer);

        listeners.forEach(builder::listener);

        return builder.build();
    }
}
```

---

## Quarkus

### Dependencies

Quarkus has its own `quarkus-langchain4j` extension, but it uses a different
programming model. The example below uses the standard LangChain4j library directly,
which works with Quarkus CDI.

=== "Gradle (Kotlin DSL)"

    ```kotlin title="build.gradle.kts"
    dependencies {
        implementation("net.agentensemble:agentensemble-core:1.3.0")
        implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
    }
    ```

=== "Maven"

    ```xml title="pom.xml"
    <dependencies>
        <dependency>
            <groupId>net.agentensemble</groupId>
            <artifactId>agentensemble-core</artifactId>
            <version>1.3.0</version>
        </dependency>
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-open-ai</artifactId>
            <version>1.11.0</version>
        </dependency>
    </dependencies>
    ```

### Wiring with CDI Producers

```java title="AgentEnsembleProducer.java"
@ApplicationScoped
public class AgentEnsembleProducer {

    @ConfigProperty(name = "agentensemble.openai.api-key")
    String apiKey;

    @ConfigProperty(name = "agentensemble.openai.model-name")
    String modelName;

    @Produces
    @ApplicationScoped
    public ChatLanguageModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    @Produces
    @ApplicationScoped
    public Agent researcher() {
        return Agent.builder()
                .role("Research Analyst")
                .goal("Find accurate, up-to-date information on the given topic")
                .backstory("You are a meticulous researcher.")
                .build();
    }

    @Produces
    @ApplicationScoped
    public Ensemble ensemble(
            ChatLanguageModel chatModel,
            Agent researcher,
            Instance<EnsembleListener> listeners) {

        Ensemble.Builder builder = Ensemble.builder()
                .chatModel(chatModel)
                .agents(researcher);

        listeners.forEach(builder::listener);

        return builder.build();
    }
}
```

### Configuration

```properties title="src/main/resources/application.properties"
agentensemble.openai.api-key=${OPENAI_API_KEY}
agentensemble.openai.model-name=gpt-4o
```

---

## Plain Java

No framework required. This is the approach shown in the
[Quickstart](../getting-started/quickstart.md).

```java title="Main.java"
public class Main {

    public static void main(String[] args) {
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        Agent researcher = Agent.builder()
                .role("Research Analyst")
                .goal("Find accurate, up-to-date information")
                .backstory("You are a meticulous researcher.")
                .build();

        Task task = Task.builder()
                .description("Research the latest trends in AI agents")
                .expectedOutput("A concise summary of key trends")
                .agent(researcher)
                .build();

        EnsembleOutput output = Ensemble.builder()
                .chatModel(model)
                .agents(researcher)
                .tasks(task)
                .build()
                .run();

        System.out.println(output.finalOutput());
    }
}
```

---

## Memory

Memory components integrate the same way across all frameworks. Create the memory
beans and pass them to the ensemble builder.

```java
LongTermMemory vectorStoreMemory = /* your LongTermMemory implementation */;

EnsembleMemory memory = EnsembleMemory.builder()
        .shortTerm(true)
        .longTerm(vectorStoreMemory)
        .longTermMaxResults(5)
        .build();

Ensemble ensemble = Ensemble.builder()
        .chatModel(chatModel)
        .agents(researcher)
        .memory(memory)
        .build();
```

In a DI framework, declare `LongTermMemory` and `EnsembleMemory` as beans and inject
them into your ensemble factory method.

See the [Memory Guide](memory.md) for details on `LongTermMemory` and `EntityMemory`
implementations.

## Tools

Tools are configured per-agent and work identically in all frameworks. See the
[Tools Guide](tools.md) and [Built-in Tools](built-in-tools.md) for available tools.

```java
Agent researcher = Agent.builder()
        .role("Research Analyst")
        .goal("Find information on the web")
        .tools(new WebSearchTool(tavilyApiKey))
        .build();
```

In a DI framework, declare tool instances as beans and inject them into your agent
factory methods.
