plugins {
    `java-library`
    application
}

// This module is not published to Maven Central -- it is an internal E2E test harness only.
// Coverage thresholds are not enforced here since there are no unit tests in this module.

application {
    mainClass.set("net.agentensemble.e2e.E2eTestServer")
}

dependencies {
    implementation(project(":agentensemble-core"))
    implementation(project(":agentensemble-review"))
    implementation(project(":agentensemble-web"))

    // LangChain4j core for ChatModel / ChatRequest / ChatResponse types
    implementation(libs.langchain4j.core)

    // Logging -- logback provides the SLF4J binding at runtime
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

// ========================
// E2E test server tasks
// ========================
//
// These tasks are consumed by Playwright's webServer config to start the Java
// server before browser tests run. Each task maps to a distinct test scenario:
//
//   sequential   -- two tasks, no review gates (port 7329)
//   with-review  -- one task with AFTER_EVERY_TASK review policy (port 7330)
//
// Usage from Playwright config (agentensemble-e2e/playwright.config.ts):
//   command: "../gradlew :agentensemble-e2e:runE2eServerSequential"
//   command: "../gradlew :agentensemble-e2e:runE2eServerWithReview"

tasks.register<JavaExec>("runE2eServerSequential") {
    group = "e2e"
    description = "Start the E2E test server in sequential scenario mode on port 7329"
    mainClass.set("net.agentensemble.e2e.E2eTestServer")
    classpath = sourceSets["main"].runtimeClasspath
    environment("E2E_SCENARIO", "sequential")
    environment("E2E_PORT", "7329")
}

tasks.register<JavaExec>("runE2eServerWithReview") {
    group = "e2e"
    description = "Start the E2E test server in with-review scenario mode on port 7330"
    mainClass.set("net.agentensemble.e2e.E2eTestServer")
    classpath = sourceSets["main"].runtimeClasspath
    environment("E2E_SCENARIO", "with-review")
    environment("E2E_PORT", "7330")
}
