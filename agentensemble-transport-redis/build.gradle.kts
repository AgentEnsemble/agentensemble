import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Coverage verification -- wired into check so CI fails if coverage drops below thresholds.
// Branch threshold is 0.70 because readPending/tryAutoClaim now have error-classification
// branches (expected vs unexpected Redis errors) whose "unexpected" paths cannot be triggered
// without corrupting the Redis protocol in tests.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.88".toBigDecimal()
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
    // Network module -- exposes RequestQueue, ResultStore, Transport SPIs to consumers.
    api(project(":agentensemble-network"))

    // Lettuce Redis client -- exposed as api because users construct RedisClient and pass it in.
    api(libs.lettuce.core)

    // JSON serialization for WorkRequest/WorkResponse over Redis.
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // Logging facade -- no implementation; users bring their own.
    implementation(libs.slf4j.api)

    // Test dependencies
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.slf4j.simple)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble Transport: Redis"
        description = "Redis Streams transport for AgentEnsemble: durable request queue and result store"
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
