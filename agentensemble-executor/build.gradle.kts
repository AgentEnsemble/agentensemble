import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Coverage verification -- wired into check so CI fails if coverage drops below thresholds.
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
    // Core ensemble API -- exposes Agent, Task, Ensemble, EnsembleOutput, EnsembleListener, etc.
    api(project(":agentensemble-core"))

    // LangChain4j core -- ChatLanguageModel is part of the ModelProvider public API
    api(libs.langchain4j.core)

    // Logging facade -- no implementation, users bring their own
    implementation(libs.slf4j.api)

    // Lombok -- compile-time only, not shipped in the jar
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

mavenPublishing {
    coordinates(
        groupId = "net.agentensemble",
        artifactId = "agentensemble-executor",
        version = "${project.version}",
    )

    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble Executor"
        description =
            "Orchestrator-agnostic task and ensemble executor for AgentEnsemble -- enables direct " +
                "in-process invocation from Temporal, AWS Step Functions, Kafka Streams, or any " +
                "external workflow engine"
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
