plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":agentensemble-core"))

    // Built-in tool modules used by examples
    implementation(project(":agentensemble-tools:calculator"))
    implementation(project(":agentensemble-tools:datetime"))
    implementation(project(":agentensemble-tools:web-search"))
    implementation(project(":agentensemble-tools:web-scraper"))
    implementation(project(":agentensemble-tools:json-parser"))
    implementation(project(":agentensemble-tools:file-read"))
    implementation(project(":agentensemble-tools:file-write"))
    implementation(project(":agentensemble-tools:process"))
    implementation(project(":agentensemble-tools:http"))

    // Metrics integration for MetricsExample
    implementation(project(":agentensemble-metrics-micrometer"))
    implementation(libs.micrometer.core)

    // OpenAI LangChain4j provider - example only
    api(libs.langchain4j.open.ai)

    // langchain4j main artifact -- provides InMemoryEmbeddingStore used in the memory example
    api(libs.langchain4j)

    // SLF4J implementation with Logback for examples
    implementation(libs.logback.classic)
}

application {
    // Default run task points to the research-writer example.
    // Use the named tasks below to run a specific example.
    mainClass = "net.agentensemble.examples.ResearchWriterExample"
}

// Named tasks for each example -- available under the "examples" task group.
//
// Usage:
//   ./gradlew :agentensemble-examples:runResearchWriter
//   ./gradlew :agentensemble-examples:runHierarchicalTeam --args="Tesla"
//   ./gradlew :agentensemble-examples:runParallelWorkflow --args="Tesla"
//   ./gradlew :agentensemble-examples:runMemoryAcrossRuns
//   ./gradlew :agentensemble-examples:runStructuredOutput --args="quantum computing"
//   ./gradlew :agentensemble-examples:runCallbacks --args="the future of AI agents"

mapOf(
    "runResearchWriter"  to "net.agentensemble.examples.ResearchWriterExample",
    "runHierarchicalTeam" to "net.agentensemble.examples.HierarchicalTeamExample",
    "runParallelWorkflow" to "net.agentensemble.examples.ParallelCompetitiveIntelligenceExample",
    "runMemoryAcrossRuns" to "net.agentensemble.examples.MemoryAcrossRunsExample",
    "runStructuredOutput" to "net.agentensemble.examples.StructuredOutputExample",
    "runCallbacks" to "net.agentensemble.examples.CallbackExample",
    "runRemoteTool" to "net.agentensemble.examples.RemoteToolExample",
    "runMetrics" to "net.agentensemble.examples.MetricsExample",
).forEach { (taskName, mainClassName) ->
    tasks.register<JavaExec>(taskName) {
        group = "examples"
        description = "Run ${mainClassName.substringAfterLast('.')}"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassName)
    }
}
