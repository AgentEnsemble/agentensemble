plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    implementation(libs.jackson.databind)
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: Process"
        description = "Subprocess execution tool for AgentEnsemble (cross-language support)"
    }
}
