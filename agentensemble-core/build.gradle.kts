plugins {
    `java-library`
}

dependencies {
    // LangChain4j core - exposed as api so users can interact with ChatLanguageModel, etc.
    api(libs.langchain4j.core)

    // JSON serialization for tool I/O
    implementation(libs.jackson.databind)

    // Logging facade - no implementation, users bring their own
    implementation(libs.slf4j.api)

    // Lombok - compile-time only, not shipped in the jar
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Test dependencies
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.slf4j.simple)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testRuntimeOnly(libs.junit.platform.launcher)
}
