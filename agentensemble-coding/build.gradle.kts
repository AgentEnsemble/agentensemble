import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Coverage verification -- wired into check so CI fails if coverage drops below thresholds.
// LINE threshold is 0.85 (not 0.90) because:
// - CodingEnsemble.run()/runIsolated() execution paths invoke Ensemble.run() which requires
//   a real LLM interaction -- these are integration-test territory, not unit-testable.
// - ToolBackendDetector "available" branches are unreachable without the optional MCP/coding
//   modules on the classpath.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.70".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
    dependsOn(tasks.named("javadoc"))
}

dependencies {
    // Workspace isolation -- exposed as api so consumers get Workspace, GitWorktreeProvider, etc.
    api(project(":agentensemble-workspace"))

    // File read tool -- always available as the MINIMAL tool backend.
    implementation(project(":agentensemble-tools:file-read"))

    // Logging facade -- no implementation; users bring their own.
    implementation(libs.slf4j.api)

    // Lombok -- compile-time only, not shipped in the JAR.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Optional tool backends -- detected at runtime via Class.forName().
    // TODO: uncomment when modules ship
    // compileOnly(project(":agentensemble-mcp"))
    // compileOnly(project(":agentensemble-tools:coding"))

    // Test dependencies
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.slf4j.simple)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble Coding"
        description = "High-level CodingAgent factory, CodingTask convenience methods, and CodingEnsemble runner for AgentEnsemble"
        url = "https://github.com/AgentEnsemble/agentensemble"

        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/licenses/MIT"
            }
        }

        developers {
            developer {
                id = "agentensemble"
                name = "AgentEnsemble"
                url = "https://github.com/AgentEnsemble"
            }
        }

        scm {
            connection = "scm:git:git://github.com/AgentEnsemble/agentensemble.git"
            developerConnection = "scm:git:ssh://github.com/AgentEnsemble/agentensemble.git"
            url = "https://github.com/AgentEnsemble/agentensemble"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/AgentEnsemble/agentensemble/issues"
        }
    }
}
