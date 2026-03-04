plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: JSON Parser"
        description = "JSON path extraction tool for AgentEnsemble"
    }
}
