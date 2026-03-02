plugins {
    application
}

dependencies {
    implementation(project(":agentensemble-core"))

    // OpenAI LangChain4j provider - example only
    implementation(libs.langchain4j.open.ai)

    // SLF4J implementation with Logback for examples
    implementation(libs.logback.classic)
}

application {
    mainClass = "io.agentensemble.examples.ResearchWriterExample"
}
