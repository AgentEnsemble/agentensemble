plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

dependencies {
    constraints {
        // Framework core
        api(project(":agentensemble-core"))

        // Optional feature modules
        api(project(":agentensemble-memory"))
        api(project(":agentensemble-review"))
        api(project(":agentensemble-reflection"))
        api(project(":agentensemble-web"))
        api(project(":agentensemble-metrics-micrometer"))
        api(project(":agentensemble-devtools"))
        api(project(":agentensemble-mcp"))

        api(project(":agentensemble-workspace"))
        api(project(":agentensemble-transport-redis"))
        api(project(":agentensemble-coding"))
        api(project(":agentensemble-executor"))

        // Individual tool modules
        api(project(":agentensemble-tools:calculator"))
        api(project(":agentensemble-tools:datetime"))
        api(project(":agentensemble-tools:json-parser"))
        api(project(":agentensemble-tools:file-read"))
        api(project(":agentensemble-tools:file-write"))
        api(project(":agentensemble-tools:web-search"))
        api(project(":agentensemble-tools:web-scraper"))
        api(project(":agentensemble-tools:process"))
        api(project(":agentensemble-tools:http"))
        api(project(":agentensemble-tools:coding"))
    }
}

mavenPublishing {
    coordinates(
        groupId = "net.agentensemble",
        artifactId = "agentensemble-bom",
        version = "${project.version}",
    )

    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble BOM"
        description = "Bill of Materials for all AgentEnsemble modules -- import once to align all versions"
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
    }
}
