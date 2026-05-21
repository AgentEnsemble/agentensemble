import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    // Phase 1 thresholds: only the in-memory ingest + browser fan-out path is covered by unit
    // tests. WebSocket-client reconnect/backoff branches and HTTP ingress fallbacks need a
    // longer-running smoke harness to exercise meaningfully and are deferred to follow-ups.
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.70".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.45".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
    dependsOn(tasks.named("javadoc"))
}

dependencies {
    // Web module -- exposed as api so consumers get the protocol records, WebDashboard,
    // ConnectionManager (for embedded reuse) and the EnsembleListener type from core.
    api(project(":agentensemble-web"))

    // Embedded server for the hub's browser-facing and ingress endpoints. Same Javalin
    // version as agentensemble-web so the two modules share Jetty + Jackson on the classpath.
    implementation(libs.javalin)

    // Jackson for envelope serialization. Already a transitive of agentensemble-web but
    // declared explicitly to make the hub's direct dependence on JsonNode visible.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.slf4j.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

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
        name = "AgentEnsemble Web Hub"
        description = "Distributed live observability hub: aggregates AgentEnsemble live events from multiple publisher processes into a single browser-facing WebSocket stream."
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
