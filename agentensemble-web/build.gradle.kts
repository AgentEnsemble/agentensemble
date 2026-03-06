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
                minimum = "0.90".toBigDecimal()
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
    // Core module - exposed as api so consumers get core types (EnsembleListener, etc.)
    api(project(":agentensemble-core"))

    // Review module - exposed as api because public types in agentensemble-web
    // (WebDashboard, WebReviewHandler, EnsembleDashboard#reviewHandler()) have direct
    // compile-time references to net.agentensemble.review.*. Declaring it compileOnly
    // would cause NoClassDefFoundError at runtime for any consumer of agentensemble-web,
    // even those that never call review gates.
    api(project(":agentensemble-review"))

    // Embedded WebSocket server -- Javalin wraps Jetty for WebSocket + static file serving.
    implementation(libs.javalin)

    // JSON serialization for the wire protocol.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // Logging facade -- no implementation; users bring their own.
    implementation(libs.slf4j.api)

    // Lombok -- compile-time only, not shipped in the JAR.
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Test dependencies
    testImplementation(project(":agentensemble-review"))
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
        name = "AgentEnsemble Web"
        description = "Live execution dashboard: embedded WebSocket server, wire protocol, and WebDashboard API for AgentEnsemble"
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
