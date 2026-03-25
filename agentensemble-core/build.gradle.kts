import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Coverage verification -- wired into check so CI fails if coverage drops below thresholds.
// Thresholds are set conservatively below the current measured levels.
// Updated after adding agentensemble-review integration (#108-#110):
//   LINE:   measured 89%  -> minimum 87%
//   BRANCH: measured 78%  -> minimum 75%
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.87".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
    dependsOn(tasks.named("javadoc"))
}

dependencies {
    // Memory module - api so it is shipped transitively to consumers. Core references
    // MemoryContext, MemoryStore, MemoryEntry, MemoryScope, and related types directly
    // in field declarations, method signatures, and execution paths across 7+ source
    // files. compileOnly would cause NoClassDefFoundError at runtime for any user who
    // depends on agentensemble-core without explicitly adding agentensemble-memory.
    // Fix for issue #147.
    api(project(":agentensemble-memory"))

    // Review module - api for the same reason: ReviewHandler, ReviewPolicy, Review,
    // ReviewDecision, ReviewRequest, ReviewTiming, and ConsoleReviewHandler are
    // referenced directly in ExecutionContext, SequentialWorkflowExecutor,
    // ParallelTaskCoordinator, AbstractAgentTool, and Ensemble. Fix for issue #147.
    api(project(":agentensemble-review"))

    // Reflection module - api so ReflectionStore, TaskReflection, InMemoryReflectionStore,
    // ReflectionConfig, ReflectionStrategy, and ReflectionInput are shipped transitively
    // to consumers. Core references these types in Task, Ensemble, ExecutionContext,
    // AgentExecutor, and AgentPromptBuilder.
    api(project(":agentensemble-reflection"))

    // TOON format -- compileOnly so it is available at compile time but optional at runtime.
    // Users who want ContextFormat.TOON add jtoon to their own runtime classpath.
    compileOnly(libs.jtoon)

    // LangChain4j core - exposed as api so users can interact with ChatModel, etc.
    api(libs.langchain4j.core)

    // JSON serialization for tool I/O and execution trace export
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

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
    testImplementation(libs.jtoon)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble Core"
        description = "Multi-agent workflow orchestration for Java, powered by LangChain4j"
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
