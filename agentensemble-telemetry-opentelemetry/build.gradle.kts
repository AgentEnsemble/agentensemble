plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

dependencies {
    api(project(":agentensemble-core"))

    // OpenTelemetry: compileOnly so users who don't want it don't pull it transitively
    compileOnly(libs.opentelemetry.api)

    // For tests: the real OpenTelemetry implementation
    testImplementation(libs.opentelemetry.api)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.slf4j.simple)
    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble Telemetry: OpenTelemetry"
        description = "OpenTelemetry integration for AgentEnsemble distributed tracing"
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
