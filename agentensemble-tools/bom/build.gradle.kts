plugins {
    `java-platform`
    id("com.vanniktech.maven.publish")
}

dependencies {
    constraints {
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
        artifactId = "agentensemble-tools-bom",
        version = "${project.version}",
    )

    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "AgentEnsemble Tools BOM"
        description = "Bill of Materials for all AgentEnsemble built-in tools"
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
