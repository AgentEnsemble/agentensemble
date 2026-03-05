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
    // agentensemble-core: exposed as api so users of devtools can access core types
    api(project(":agentensemble-core"))

    // Jackson for DagModel JSON serialization
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // Lombok - compile-time only, not shipped in the jar
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.slf4j.simple)

    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble DevTools"
        description = "Developer tooling for AgentEnsemble: DAG export, trace utilities, and execution graph visualization support"
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
