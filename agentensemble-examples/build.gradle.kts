plugins {
    `java-library`
    application
}

dependencies {
    implementation(project(":agentensemble-core"))
    implementation(project(":agentensemble-memory"))
    implementation(project(":agentensemble-review"))

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

    // LangChain4j provider and main artifact exposed as api so they are
    // available on the compile classpath of any module that depends on examples.
    api(libs.langchain4j.open.ai)
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
//   ./gradlew :agentensemble-examples:runHierarchicalTeam --args="Acme Corp"
//   ./gradlew :agentensemble-examples:runParallelWorkflow --args="Acme Corp enterprise software"
//   ./gradlew :agentensemble-examples:runDynamicAgents
//   ./gradlew :agentensemble-examples:runDynamicAgents --args="Risotto Steak Tiramisu"
//   ./gradlew :agentensemble-examples:runMemoryAcrossRuns
//   ./gradlew :agentensemble-examples:runStructuredOutput --args="quantum computing"
//   ./gradlew :agentensemble-examples:runCallbacks --args="the future of AI agents"
//   ./gradlew :agentensemble-examples:runCaptureMode
//   ./gradlew :agentensemble-examples:runHumanInTheLoop --args="AgentEnsemble v2"
//   ./gradlew :agentensemble-examples:runCrossRunMemory --args="renewable energy"
//   ./gradlew :agentensemble-examples:runToolPipeline

mapOf(
    "runResearchWriter"  to "net.agentensemble.examples.ResearchWriterExample",
    "runHierarchicalTeam" to "net.agentensemble.examples.HierarchicalTeamExample",
    "runParallelWorkflow" to "net.agentensemble.examples.ParallelCompetitiveIntelligenceExample",
    "runDynamicAgents" to "net.agentensemble.examples.DynamicAgentsExample",
    "runMapReduceKitchen" to "net.agentensemble.examples.MapReduceKitchenExample",
    "runMapReduceAdaptiveKitchen" to "net.agentensemble.examples.MapReduceAdaptiveKitchenExample",
    "runMapReduceTaskFirstKitchen" to "net.agentensemble.examples.MapReduceTaskFirstKitchenExample",
    "runMemoryAcrossRuns" to "net.agentensemble.examples.MemoryAcrossRunsExample",
    "runStructuredOutput" to "net.agentensemble.examples.StructuredOutputExample",
    "runCallbacks" to "net.agentensemble.examples.CallbackExample",
    "runRemoteTool" to "net.agentensemble.examples.RemoteToolExample",
    "runMetrics" to "net.agentensemble.examples.MetricsExample",
    "runCaptureMode" to "net.agentensemble.examples.CaptureModeExample",
    "runHumanInTheLoop" to "net.agentensemble.examples.HumanInTheLoopExample",
    "runCrossRunMemory" to "net.agentensemble.examples.CrossRunMemoryExample",
    "runToolPipeline" to "net.agentensemble.examples.ToolPipelineExample",
    "runDeterministicTask" to "net.agentensemble.examples.DeterministicTaskExample",
).forEach { (taskName, mainClassName) ->
    tasks.register<JavaExec>(taskName) {
        group = "examples"
        description = "Run ${mainClassName.substringAfterLast('.')}"
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set(mainClassName)
    }
}
