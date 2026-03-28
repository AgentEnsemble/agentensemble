import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Coverage verification -- wired into check so CI fails if coverage drops below thresholds.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    violationRules {
        rule {
            // Lines that create real MCP clients (fromServer, buildClient, lazy start) are
            // untestable without a running MCP server. All testable logic is at 100%.
            limit {
                counter = "LINE"
                minimum = "0.88".toBigDecimal()
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
    // Core module -- exposed as api so consumers get AgentTool, ToolResult, CustomSchemaAgentTool.
    api(project(":agentensemble-core"))

    // LangChain4j MCP client -- exposed as api so consumers can supply McpTransport instances.
    api(libs.langchain4j.mcp)

    // JSON serialization for parsing tool arguments.
    implementation(libs.jackson.databind)

    // Logging facade -- no implementation; users bring their own.
    implementation(libs.slf4j.api)

    // Test dependencies
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
        name = "AgentEnsemble MCP"
        description = "MCP protocol bridge for AgentEnsemble: adapts MCP server tools to the AgentTool interface"
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
